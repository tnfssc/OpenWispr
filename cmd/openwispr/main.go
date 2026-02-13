package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/godbus/dbus/v5"
)

const (
	extensionBusName   = "org.gnome.Shell.Extensions.OpenWispr"
	extensionShellBus  = "org.gnome.Shell"
	extensionPath      = dbus.ObjectPath("/org/gnome/Shell/Extensions/OpenWispr")
	extensionInterface = "org.gnome.Shell.Extensions.OpenWispr"

	companionBusName   = "io.github.tnfssc.OpenWispr.Recorder"
	companionPath      = dbus.ObjectPath("/io/github/tnfssc/OpenWispr/Recorder")
	companionInterface = "io.github.tnfssc.OpenWispr.Recorder"

	portalBusName          = "org.freedesktop.portal.Desktop"
	portalDesktopPath      = dbus.ObjectPath("/org/freedesktop/portal/desktop")
	portalGSInterface      = "org.freedesktop.portal.GlobalShortcuts"
	portalRequestInterface = "org.freedesktop.portal.Request"
	portalSessionInterface = "org.freedesktop.portal.Session"
	portalRegistryIface    = "org.freedesktop.host.portal.Registry"

	defaultPortalTrigger = "Alt_R"
	defaultEvdevDevice   = "/dev/input/by-path/platform-i8042-serio-0-event-kbd"
	shortcutID           = "openwispr-hold"
	portalAppID          = "io.github.tnfssc.openwispr"
)

type extensionClient struct {
	conn *dbus.Conn
	objs []dbus.BusObject
}

type status struct {
	Recording  bool
	Processing bool
	Trigger    string
}

type shortcutBinding struct {
	ID      string
	Options map[string]dbus.Variant
}

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(1)
	}

	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		fatalf("failed to connect to session bus: %v", err)
	}
	defer conn.Close()

	client := &extensionClient{
		conn: conn,
		objs: []dbus.BusObject{
			conn.Object(extensionBusName, extensionPath),
			conn.Object(extensionShellBus, extensionPath),
		},
	}

	switch os.Args[1] {
	case "toggle":
		recording, err := client.Toggle("cli")
		if err != nil {
			fatalf("toggle failed: %v", err)
		}
		fmt.Printf("recording=%t\n", recording)
	case "start":
		started, err := client.Start("cli")
		if err != nil {
			fatalf("start failed: %v", err)
		}
		fmt.Printf("started=%t\n", started)
	case "stop":
		stopped, err := client.Stop(true, "cli")
		if err != nil {
			fatalf("stop failed: %v", err)
		}
		fmt.Printf("stopped=%t\n", stopped)
	case "status":
		s, err := client.Status()
		if err != nil {
			fatalf("status failed: %v", err)
		}
		fmt.Printf("recording=%t processing=%t trigger=%q\n", s.Recording, s.Processing, s.Trigger)
	case "doctor":
		if err := runDoctor(conn, client); err != nil {
			fatalf("doctor checks failed: %v", err)
		}
	case "daemon":
		if err := runDaemon(os.Args[2:], conn, client); err != nil {
			fatalf("daemon failed: %v", err)
		}
	case "engine":
		if err := runEngine(conn); err != nil {
			fatalf("engine failed: %v", err)
		}
	default:
		usage()
		os.Exit(1)
	}
}

func runDaemon(args []string, conn *dbus.Conn, client *extensionClient) error {
	fs := flag.NewFlagSet("daemon", flag.ExitOnError)
	backend := fs.String("backend", "auto", "daemon backend: auto|portal|evdev")
	trigger := fs.String("trigger", defaultPortalTrigger, "portal preferred trigger (shortcuts spec format)")
	device := fs.String("device", defaultEvdevDevice, "evdev keyboard device path")
	key := fs.String("evdev-key", "rightalt", "evdev key: rightalt|leftalt")
	_ = fs.Parse(args)

	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()

	log.Printf("starting openwispr daemon backend=%s", *backend)

	switch strings.ToLower(*backend) {
	case "portal":
		return runPortalDaemon(ctx, conn, client, *trigger)
	case "evdev":
		return runEvdevDaemon(ctx, client, *device, *key)
	case "auto":
		if err := runPortalDaemon(ctx, conn, client, *trigger); err == nil || ctx.Err() != nil {
			return err
		}

		log.Printf("portal backend unavailable, falling back to evdev")
		return runEvdevDaemon(ctx, client, *device, *key)
	default:
		return fmt.Errorf("unsupported backend: %s", *backend)
	}
}

func runPortalDaemon(ctx context.Context, conn *dbus.Conn, client *extensionClient, preferredTrigger string) error {
	if err := registerPortalAppID(conn); err != nil {
		return fmt.Errorf("prepare portal app id: %w", err)
	}

	sessionHandle, err := portalCreateSession(conn)
	if err != nil {
		return fmt.Errorf("create portal session: %w", err)
	}
	defer closePortalSession(conn, sessionHandle)

	if err := portalBindShortcut(conn, sessionHandle, preferredTrigger); err != nil {
		return fmt.Errorf("bind portal shortcut: %w", err)
	}

	log.Printf("portal shortcut session ready trigger=%s", preferredTrigger)
	portalSource := fmt.Sprintf("portal:%s", preferredTrigger)

	if err := conn.AddMatchSignal(
		dbus.WithMatchObjectPath(portalDesktopPath),
		dbus.WithMatchInterface(portalGSInterface),
		dbus.WithMatchMember("Activated"),
	); err != nil {
		return fmt.Errorf("add signal match for Activated: %w", err)
	}
	defer func() {
		_ = conn.RemoveMatchSignal(
			dbus.WithMatchObjectPath(portalDesktopPath),
			dbus.WithMatchInterface(portalGSInterface),
			dbus.WithMatchMember("Activated"),
		)
	}()

	if err := conn.AddMatchSignal(
		dbus.WithMatchObjectPath(portalDesktopPath),
		dbus.WithMatchInterface(portalGSInterface),
		dbus.WithMatchMember("Deactivated"),
	); err != nil {
		return fmt.Errorf("add signal match for Deactivated: %w", err)
	}
	defer func() {
		_ = conn.RemoveMatchSignal(
			dbus.WithMatchObjectPath(portalDesktopPath),
			dbus.WithMatchInterface(portalGSInterface),
			dbus.WithMatchMember("Deactivated"),
		)
	}()

	signalCh := make(chan *dbus.Signal, 32)
	conn.Signal(signalCh)
	defer conn.RemoveSignal(signalCh)
	portalHeld := false

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-conn.Context().Done():
			return errors.New("session bus connection closed")
		case sig := <-signalCh:
			if sig == nil {
				continue
			}

			if sig.Name != portalGSInterface+".Activated" && sig.Name != portalGSInterface+".Deactivated" {
				continue
			}

			if len(sig.Body) < 2 {
				continue
			}

			receivedSession, ok := sig.Body[0].(dbus.ObjectPath)
			if !ok || receivedSession != sessionHandle {
				continue
			}

			id, ok := sig.Body[1].(string)
			if !ok || id != shortcutID {
				continue
			}

			if sig.Name == portalGSInterface+".Activated" {
				if portalHeld {
					portalHeld = false
					stopped, err := client.Stop(true, portalSource)
					if err != nil {
						log.Printf("portal activated-release fallback but stop failed: %v", err)
						continue
					}
					log.Printf("portal activated-release fallback stop=%t", stopped)
					continue
				}

				started, err := client.Start(portalSource)
				if err != nil {
					log.Printf("portal activated but start failed: %v", err)
					continue
				}
				if started {
					portalHeld = true
				}
				log.Printf("portal activated start=%t", started)
				continue
			}

			portalHeld = false
			stopped, err := client.Stop(true, portalSource)
			if err != nil {
				log.Printf("portal deactivated but stop failed: %v", err)
				continue
			}
			log.Printf("portal deactivated stop=%t", stopped)
		}
	}
}

func registerPortalAppID(conn *dbus.Conn) error {
	registry := conn.Object(portalBusName, portalDesktopPath)
	options := map[string]dbus.Variant{}
	call := registry.Call(portalRegistryIface+".Register", 0, portalAppID, options)
	if call.Err != nil {
		return call.Err
	}

	return nil
}

func runDoctor(conn *dbus.Conn, client *extensionClient) error {
	if _, err := client.Status(); err != nil {
		return fmt.Errorf("extension control interface unavailable: %w", err)
	}
	fmt.Println("[ok] extension DBus control reachable")

	portalObj := conn.Object(portalBusName, portalDesktopPath)
	version, err := readUint32Property(portalObj, portalGSInterface, "version")
	if err != nil {
		return fmt.Errorf("portal globalshortcuts unavailable: %w", err)
	}
	fmt.Printf("[ok] portal GlobalShortcuts version=%d\n", version)

	companionObj := conn.Object(companionBusName, companionPath)
	var engineRecording, engineProcessing bool
	if err := companionObj.Call(companionInterface+".Status", 0).Store(&engineRecording, &engineProcessing); err != nil {
		fmt.Printf("[warn] companion engine unavailable: %v\n", err)
	} else {
		fmt.Printf("[ok] companion engine reachable recording=%t processing=%t\n", engineRecording, engineProcessing)
	}

	if _, err := os.Open(defaultEvdevDevice); err != nil {
		fmt.Printf("[warn] cannot read %s: %v\n", defaultEvdevDevice, err)
	} else {
		fmt.Printf("[ok] evdev device readable: %s\n", defaultEvdevDevice)
	}

	return nil
}

func portalCreateSession(conn *dbus.Conn) (dbus.ObjectPath, error) {
	obj := conn.Object(portalBusName, portalDesktopPath)
	handleToken := fmt.Sprintf("openwispr_hs_%d", time.Now().UnixNano())
	sessionToken := fmt.Sprintf("openwispr_session_%d", time.Now().UnixNano())
	expectedRequestPath, err := portalRequestPathForToken(conn, handleToken)
	if err != nil {
		return "", err
	}

	sigCh, cleanup, err := watchRequestResponses(conn)
	if err != nil {
		return "", err
	}
	defer cleanup()

	options := map[string]dbus.Variant{
		"handle_token":         dbus.MakeVariant(handleToken),
		"session_handle_token": dbus.MakeVariant(sessionToken),
	}

	var requestPath dbus.ObjectPath
	if err := obj.Call(portalGSInterface+".CreateSession", 0, options).Store(&requestPath); err != nil {
		return "", err
	}

	responseCode, results, err := awaitRequestResponse(sigCh, []dbus.ObjectPath{requestPath, expectedRequestPath}, 15*time.Second)
	if err != nil {
		return "", err
	}
	if responseCode != 0 {
		return "", fmt.Errorf("portal request returned code %d", responseCode)
	}

	sessionAny, ok := results["session_handle"]
	if !ok {
		return "", errors.New("portal response missing session_handle")
	}

	if path, ok := sessionAny.Value().(dbus.ObjectPath); ok {
		return path, nil
	}

	if pathStr, ok := sessionAny.Value().(string); ok {
		return dbus.ObjectPath(pathStr), nil
	}

	return "", fmt.Errorf("unexpected session_handle type: %T", sessionAny.Value())
}

func portalBindShortcut(conn *dbus.Conn, sessionHandle dbus.ObjectPath, preferredTrigger string) error {
	obj := conn.Object(portalBusName, portalDesktopPath)
	handleToken := fmt.Sprintf("openwispr_bind_%d", time.Now().UnixNano())
	expectedRequestPath, err := portalRequestPathForToken(conn, handleToken)
	if err != nil {
		return err
	}

	sigCh, cleanup, err := watchRequestResponses(conn)
	if err != nil {
		return err
	}
	defer cleanup()

	shortcuts := []shortcutBinding{
		{
			ID: shortcutID,
			Options: map[string]dbus.Variant{
				"description":       dbus.MakeVariant("Hold to talk for openwispr"),
				"preferred_trigger": dbus.MakeVariant(preferredTrigger),
			},
		},
	}

	options := map[string]dbus.Variant{
		"handle_token": dbus.MakeVariant(handleToken),
	}

	var requestPath dbus.ObjectPath
	if err := obj.Call(portalGSInterface+".BindShortcuts", 0, sessionHandle, shortcuts, "", options).Store(&requestPath); err != nil {
		return err
	}

	responseCode, _, err := awaitRequestResponse(sigCh, []dbus.ObjectPath{requestPath, expectedRequestPath}, 30*time.Second)
	if err != nil {
		return err
	}
	if responseCode != 0 {
		return fmt.Errorf("bind shortcuts request returned code %d", responseCode)
	}

	return nil
}

func watchRequestResponses(conn *dbus.Conn) (chan *dbus.Signal, func(), error) {
	if err := conn.AddMatchSignal(
		dbus.WithMatchInterface(portalRequestInterface),
		dbus.WithMatchMember("Response"),
	); err != nil {
		return nil, nil, err
	}

	sigCh := make(chan *dbus.Signal, 4)
	conn.Signal(sigCh)

	cleanup := func() {
		conn.RemoveSignal(sigCh)
		_ = conn.RemoveMatchSignal(
			dbus.WithMatchInterface(portalRequestInterface),
			dbus.WithMatchMember("Response"),
		)
	}

	return sigCh, cleanup, nil
}

func awaitRequestResponse(sigCh <-chan *dbus.Signal, requestPaths []dbus.ObjectPath, timeout time.Duration) (uint32, map[string]dbus.Variant, error) {
	allowed := map[dbus.ObjectPath]struct{}{}
	for _, path := range requestPaths {
		if path == "" {
			continue
		}
		allowed[path] = struct{}{}
	}

	timer := time.NewTimer(timeout)
	defer timer.Stop()

	for {
		select {
		case sig := <-sigCh:
			if sig == nil || sig.Name != portalRequestInterface+".Response" {
				continue
			}
			if len(allowed) > 0 {
				if _, ok := allowed[sig.Path]; !ok {
					continue
				}
			}
			if len(sig.Body) != 2 {
				return 0, nil, fmt.Errorf("unexpected response payload size: %d", len(sig.Body))
			}

			code, ok := sig.Body[0].(uint32)
			if !ok {
				return 0, nil, fmt.Errorf("unexpected response code type: %T", sig.Body[0])
			}

			results, ok := sig.Body[1].(map[string]dbus.Variant)
			if !ok {
				return 0, nil, fmt.Errorf("unexpected response results type: %T", sig.Body[1])
			}

			return code, results, nil
		case <-timer.C:
			return 0, nil, fmt.Errorf("timeout waiting for portal response")
		}
	}
}

func portalRequestPathForToken(conn *dbus.Conn, token string) (dbus.ObjectPath, error) {
	if token == "" {
		return "", errors.New("empty portal handle token")
	}

	var unique string
	for _, name := range conn.Names() {
		if strings.HasPrefix(name, ":") {
			unique = name
			break
		}
	}

	if unique == "" {
		return "", errors.New("could not determine unique DBus name")
	}

	sanitizedSender := strings.NewReplacer(":", "", ".", "_").Replace(unique)
	path := fmt.Sprintf("/org/freedesktop/portal/desktop/request/%s/%s", sanitizedSender, token)
	if !dbus.ObjectPath(path).IsValid() {
		return "", fmt.Errorf("invalid portal request path: %s", path)
	}

	return dbus.ObjectPath(path), nil
}

func closePortalSession(conn *dbus.Conn, sessionHandle dbus.ObjectPath) {
	sessionObj := conn.Object(portalBusName, sessionHandle)
	call := sessionObj.Call(portalSessionInterface+".Close", 0)
	if call.Err != nil {
		log.Printf("portal session close failed: %v", call.Err)
	}
}

func (c *extensionClient) Toggle(source string) (bool, error) {
	var recording bool
	err := c.callStore(extensionInterface+".Toggle", &recording, source)
	return recording, err
}

func (c *extensionClient) Start(source string) (bool, error) {
	var started bool
	err := c.callStore(extensionInterface+".Start", &started, source)
	return started, err
}

func (c *extensionClient) Stop(transcribe bool, source string) (bool, error) {
	var stopped bool
	err := c.callStore(extensionInterface+".Stop", &stopped, transcribe, source)
	return stopped, err
}

func (c *extensionClient) Status() (status, error) {
	var s status
	err := c.callStore(extensionInterface+".Status", []any{&s.Recording, &s.Processing, &s.Trigger})
	return s, err
}

func (c *extensionClient) callStore(method string, storeTargets any, args ...any) error {
	var lastErr error

	for _, obj := range c.objs {
		call := obj.Call(method, 0, args...)

		var err error
		switch targets := storeTargets.(type) {
		case []any:
			err = call.Store(targets...)
		default:
			err = call.Store(targets)
		}

		if err == nil {
			return nil
		}

		lastErr = err
		if !isUnavailableDBusError(err) {
			return err
		}
	}

	if lastErr != nil {
		return lastErr
	}

	return errors.New("no DBus destinations configured")
}

func isUnavailableDBusError(err error) bool {
	var dbusErr dbus.Error
	if !errors.As(err, &dbusErr) {
		return false
	}

	switch dbusErr.Name {
	case "org.freedesktop.DBus.Error.ServiceUnknown",
		"org.freedesktop.DBus.Error.NameHasNoOwner",
		"org.freedesktop.DBus.Error.UnknownObject",
		"org.freedesktop.DBus.Error.UnknownMethod":
		return true
	default:
		return false
	}
}

func readUint32Property(obj dbus.BusObject, iface, property string) (uint32, error) {
	var value dbus.Variant
	err := obj.Call("org.freedesktop.DBus.Properties.Get", 0, iface, property).Store(&value)
	if err != nil {
		return 0, err
	}

	u32, ok := value.Value().(uint32)
	if !ok {
		return 0, fmt.Errorf("unexpected property type: %T", value.Value())
	}

	return u32, nil
}

func usage() {
	fmt.Println(`openwispr companion CLI

Usage:
  openwispr toggle
  openwispr start
  openwispr stop
  openwispr status
  openwispr doctor
  openwispr daemon [--backend auto|portal|evdev] [--trigger Alt_R] [--device /dev/input/... ] [--evdev-key rightalt]
  openwispr engine
`)
}

func fatalf(format string, args ...any) {
	log.Printf(format, args...)
	os.Exit(1)
}
