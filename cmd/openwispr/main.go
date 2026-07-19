package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/exec"
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
	extensionsBusName      = "org.gnome.Shell.Extensions"
	extensionsPath         = dbus.ObjectPath("/org/gnome/Shell/Extensions")
	extensionsInterface    = "org.gnome.Shell.Extensions"

	defaultPortalTrigger = "<Super>z"
	defaultEvdevDevice   = "/dev/input/by-path/platform-i8042-serio-0-event-kbd"
	shortcutID           = "openwispr-hold"
	portalAppID          = "io.github.tnfssc.openwispr"
	extensionUUID        = "openwispr-gnome-extension@tnfssc.github.com"

	// setupCallTimeout bounds portal setup D-Bus calls (Register,
	// CreateSession, BindShortcuts, Properties.Get). A wedged portal must
	// not hang daemon boot indefinitely.
	setupCallTimeout = 30 * time.Second

	// restartSettleDelay lets systemd/userdb propagate service state
	// before health checks run after a restart.
	restartSettleDelay = 400 * time.Millisecond

	// portalSignalBufferSize bounds the portal Activated/Deactivated
	// signal queue. GNOME Shell may burst-emit activation pairs; 32
	// absorbs a short burst without blocking the dispatch goroutine.
	portalSignalBufferSize = 32

	// requestSignalBufferSize bounds the portal Request.Response queue.
	// One Response is expected per outstanding request; 4 covers setup
	// plus late duplicate deliveries.
	requestSignalBufferSize = 4
)

type extensionClient struct {
	conn *dbus.Conn
	objs []dbus.BusObject

	// ctx bounds in-flight D-Bus calls so SIGINT/SIGTERM can interrupt
	// them during shutdown. It is stored on the client rather than
	// threaded through Start/Stop/Status because the evdev backend —
	// maintained in a separate file outside this PR's scope — calls those
	// methods without an explicit context. Set once at construction; the
	// client lives only for the duration of run().
	ctx context.Context
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
	if err := run(os.Args); err != nil {
		fmt.Fprintf(os.Stderr, "openwispr: %v\n", err)
		os.Exit(1)
	}
}

// run dispatches the CLI subcommand. It owns the session bus connection
// and returns any error so main() can print it without a date/time prefix
// and let deferred cleanup (conn.Close) run on every exit path.
func run(args []string) error {
	if len(args) < 2 {
		usage()
		return errors.New("no subcommand specified")
	}

	conn, err := dbus.ConnectSessionBus()
	if err != nil {
		return fmt.Errorf("failed to connect to session bus: %w", err)
	}
	defer conn.Close()

	// A signal context covers every subcommand so SIGINT/SIGTERM can
	// interrupt in-flight D-Bus calls and systemctl invocations.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	client := &extensionClient{
		conn: conn,
		objs: []dbus.BusObject{
			conn.Object(extensionBusName, extensionPath),
			conn.Object(extensionShellBus, extensionPath),
		},
		ctx: ctx,
	}

	switch args[1] {
	case "toggle":
		recording, err := client.Toggle("cli")
		if err != nil {
			return fmt.Errorf("toggle failed: %w", err)
		}
		fmt.Printf("recording=%t\n", recording)
	case "start":
		started, err := client.Start("cli")
		if err != nil {
			return fmt.Errorf("start failed: %w", err)
		}
		fmt.Printf("started=%t\n", started)
	case "stop":
		stopped, err := client.Stop(true, "cli")
		if err != nil {
			return fmt.Errorf("stop failed: %w", err)
		}
		fmt.Printf("stopped=%t\n", stopped)
	case "status":
		s, err := client.Status()
		if err != nil {
			return fmt.Errorf("status failed: %w", err)
		}
		fmt.Printf("recording=%t processing=%t trigger=%q\n", s.Recording, s.Processing, s.Trigger)
	case "doctor":
		if err := runDoctor(ctx, conn, client); err != nil {
			return fmt.Errorf("doctor checks failed: %w", err)
		}
	case "restart":
		if err := runRestart(ctx, args[2:], conn, client); err != nil {
			return fmt.Errorf("restart failed: %w", err)
		}
	case "daemon":
		if err := runDaemon(args[2:], ctx, conn, client); err != nil {
			return fmt.Errorf("daemon failed: %w", err)
		}
	case "engine":
		if err := runEngine(conn); err != nil {
			return fmt.Errorf("engine failed: %w", err)
		}
	default:
		usage()
		return fmt.Errorf("unknown subcommand %q", args[1])
	}
	return nil
}

func runDaemon(args []string, ctx context.Context, conn *dbus.Conn, client *extensionClient) error {
	fs := flag.NewFlagSet("daemon", flag.ExitOnError)
	backend := fs.String("backend", "auto", "daemon backend: auto|portal|evdev")
	trigger := fs.String("trigger", defaultPortalTrigger, "portal preferred trigger (shortcuts spec format)")
	device := fs.String("device", defaultEvdevDevice, "evdev keyboard device path")
	key := fs.String("evdev-key", "z", "evdev key: z|capslock|rightalt|leftalt")
	fs.Parse(args)

	log.Printf("starting openwispr daemon backend=%s", *backend)

	switch strings.ToLower(*backend) {
	case "portal":
		return runPortalDaemon(ctx, conn, client, *trigger)
	case "evdev":
		return runEvdevDaemon(ctx, client, *device, *key)
	case "auto":
		portalErr := runPortalDaemon(ctx, conn, client, *trigger)
		if portalErr == nil || ctx.Err() != nil {
			return portalErr
		}
		// Any portal failure — including startup-unavailable — falls
		// back to evdev so the daemon can still serve the trigger key.
		log.Printf("portal backend failed (%v), falling back to evdev", portalErr)
		return runEvdevDaemon(ctx, client, *device, *key)
	default:
		return fmt.Errorf("unsupported backend: %s", *backend)
	}
}

// isPortalUnavailable reports whether err is a D-Bus error indicating the
// target service, interface, object, or method is not present on the bus.
// It is used both to retry the next bus name in callStore and (formerly)
// to decide evdev fallback when the portal is missing at startup. The two
// previous helpers disagreed on UnknownInterface membership; this single
// predicate reconciles them.
func isPortalUnavailable(err error) bool {
	var dbusErr dbus.Error
	if !errors.As(err, &dbusErr) {
		return false
	}

	switch dbusErr.Name {
	case "org.freedesktop.DBus.Error.ServiceUnknown",
		"org.freedesktop.DBus.Error.NameHasNoOwner",
		"org.freedesktop.DBus.Error.UnknownObject",
		"org.freedesktop.DBus.Error.UnknownMethod",
		"org.freedesktop.DBus.Error.UnknownInterface":
		return true
	default:
		return false
	}
}

func runPortalDaemon(ctx context.Context, conn *dbus.Conn, client *extensionClient, preferredTrigger string) error {
	if err := registerPortalAppID(ctx, conn); err != nil {
		return fmt.Errorf("prepare portal app id: %w", err)
	}

	sessionHandle, err := portalCreateSession(ctx, conn)
	if err != nil {
		return fmt.Errorf("create portal session: %w", err)
	}
	defer closePortalSession(conn, sessionHandle)

	if err := portalBindShortcut(ctx, conn, sessionHandle, preferredTrigger); err != nil {
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

	signalCh := make(chan *dbus.Signal, portalSignalBufferSize)
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

func registerPortalAppID(ctx context.Context, conn *dbus.Conn) error {
	registry := conn.Object(portalBusName, portalDesktopPath)

	callCtx, cancel := context.WithTimeout(ctx, setupCallTimeout)
	defer cancel()

	options := map[string]dbus.Variant{}
	call := registry.CallWithContext(callCtx, portalRegistryIface+".Register", 0, portalAppID, options)
	if call.Err != nil {
		return fmt.Errorf("register portal app id: %w", call.Err)
	}

	return nil
}

func runDoctor(ctx context.Context, conn *dbus.Conn, client *extensionClient) error {
	if _, err := client.Status(); err != nil {
		return fmt.Errorf("extension control interface unavailable: %w", err)
	}
	fmt.Println("[ok] extension DBus control reachable")

	portalObj := conn.Object(portalBusName, portalDesktopPath)
	version, err := readUint32Property(ctx, portalObj, portalGSInterface, "version")
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

	f, err := os.Open(defaultEvdevDevice)
	if err != nil {
		fmt.Printf("[warn] cannot read %s: %v\n", defaultEvdevDevice, err)
	} else {
		defer f.Close()
		fmt.Printf("[ok] evdev device readable: %s\n", defaultEvdevDevice)
	}

	return nil
}

func runRestart(ctx context.Context, args []string, conn *dbus.Conn, client *extensionClient) error {
	fs := flag.NewFlagSet("restart", flag.ExitOnError)
	skipExtensionReload := fs.Bool("no-extension-reload", false, "do not disable/enable the GNOME extension")
	fs.Parse(args)

	fmt.Println("[step] restarting portal services")
	if err := runUserSystemctl(ctx, "restart", "xdg-desktop-portal-gnome.service"); err != nil {
		fmt.Printf("[warn] could not restart xdg-desktop-portal-gnome.service: %v\n", err)
	}
	if err := runUserSystemctl(ctx, "restart", "xdg-desktop-portal.service"); err != nil {
		fmt.Printf("[warn] could not restart xdg-desktop-portal.service: %v\n", err)
	}

	fmt.Println("[step] restarting openwispr services")
	if err := runUserSystemctl(ctx, "restart", "openwispr-engine.service"); err != nil {
		return fmt.Errorf("restart openwispr-engine.service: %w", err)
	}
	if err := runUserSystemctl(ctx, "restart", "openwispr-hotkeyd.service"); err != nil {
		fmt.Printf("[warn] could not restart openwispr-hotkeyd.service: %v\n", err)
	}

	if !*skipExtensionReload {
		fmt.Println("[step] reloading extension")
		if err := reloadExtension(conn); err != nil {
			fmt.Printf("[warn] could not reload extension over DBus: %v\n", err)
		}
	}

	select {
	case <-time.After(restartSettleDelay):
	case <-ctx.Done():
		return ctx.Err()
	}
	fmt.Println("[step] running health checks")
	if err := runDoctor(ctx, conn, client); err != nil {
		return err
	}

	fmt.Println("[ok] openwispr restart completed")
	return nil
}

func runUserSystemctl(ctx context.Context, args ...string) error {
	cmd := exec.CommandContext(ctx, "systemctl", append([]string{"--user"}, args...)...)
	output, err := cmd.CombinedOutput()
	if err != nil {
		trimmed := strings.TrimSpace(string(output))
		if trimmed == "" {
			return err
		}
		return fmt.Errorf("%w (%s)", err, trimmed)
	}

	return nil
}

func reloadExtension(conn *dbus.Conn) error {
	obj := conn.Object(extensionsBusName, extensionsPath)

	var disabled bool
	if err := obj.Call(extensionsInterface+".DisableExtension", 0, extensionUUID).Store(&disabled); err != nil {
		return fmt.Errorf("disable extension: %w", err)
	}

	var enabled bool
	if err := obj.Call(extensionsInterface+".EnableExtension", 0, extensionUUID).Store(&enabled); err != nil {
		return fmt.Errorf("enable extension: %w", err)
	}

	if !enabled {
		return fmt.Errorf("extension %s did not report enabled", extensionUUID)
	}

	return nil
}

func portalCreateSession(ctx context.Context, conn *dbus.Conn) (dbus.ObjectPath, error) {
	obj := conn.Object(portalBusName, portalDesktopPath)
	now := time.Now().UnixNano()
	handleToken := fmt.Sprintf("openwispr_hs_%d", now)
	sessionToken := fmt.Sprintf("openwispr_session_%d", now)
	expectedRequestPath, err := portalRequestPathForToken(conn, handleToken)
	if err != nil {
		return "", fmt.Errorf("portal request path: %w", err)
	}

	sigCh, cleanup, err := watchRequestResponses(conn)
	if err != nil {
		return "", fmt.Errorf("watch portal responses: %w", err)
	}
	defer cleanup()

	options := map[string]dbus.Variant{
		"handle_token":         dbus.MakeVariant(handleToken),
		"session_handle_token": dbus.MakeVariant(sessionToken),
	}

	callCtx, cancel := context.WithTimeout(ctx, setupCallTimeout)
	defer cancel()
	var requestPath dbus.ObjectPath
	if err := obj.CallWithContext(callCtx, portalGSInterface+".CreateSession", 0, options).Store(&requestPath); err != nil {
		return "", fmt.Errorf("create portal session: %w", err)
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

func portalBindShortcut(ctx context.Context, conn *dbus.Conn, sessionHandle dbus.ObjectPath, preferredTrigger string) error {
	obj := conn.Object(portalBusName, portalDesktopPath)
	handleToken := fmt.Sprintf("openwispr_bind_%d", time.Now().UnixNano())
	expectedRequestPath, err := portalRequestPathForToken(conn, handleToken)
	if err != nil {
		return fmt.Errorf("portal request path: %w", err)
	}

	sigCh, cleanup, err := watchRequestResponses(conn)
	if err != nil {
		return fmt.Errorf("watch portal responses: %w", err)
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

	callCtx, cancel := context.WithTimeout(ctx, setupCallTimeout)
	defer cancel()
	var requestPath dbus.ObjectPath
	if err := obj.CallWithContext(callCtx, portalGSInterface+".BindShortcuts", 0, sessionHandle, shortcuts, "", options).Store(&requestPath); err != nil {
		return fmt.Errorf("bind portal shortcuts: %w", err)
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

	sigCh := make(chan *dbus.Signal, requestSignalBufferSize)
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
		call := obj.CallWithContext(c.ctx, method, 0, args...)

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
		if !isPortalUnavailable(err) {
			return fmt.Errorf("dbus call %s: %w", method, err)
		}
	}

	if lastErr != nil {
		return fmt.Errorf("dbus call %s: %w", method, lastErr)
	}

	return errors.New("no DBus destinations configured")
}

func readUint32Property(ctx context.Context, obj dbus.BusObject, iface, property string) (uint32, error) {
	callCtx, cancel := context.WithTimeout(ctx, setupCallTimeout)
	defer cancel()
	var value dbus.Variant
	err := obj.CallWithContext(callCtx, "org.freedesktop.DBus.Properties.Get", 0, iface, property).Store(&value)
	if err != nil {
		return 0, fmt.Errorf("get property %s.%s: %w", iface, property, err)
	}

	u32, ok := value.Value().(uint32)
	if !ok {
		return 0, fmt.Errorf("unexpected property type: %T", value.Value())
	}

	return u32, nil
}

func usage() {
	fmt.Print(`openwispr companion CLI

Usage:
  openwispr toggle
  openwispr start
  openwispr stop
  openwispr status
  openwispr doctor
  openwispr restart [--no-extension-reload]
  openwispr daemon [--backend auto|portal|evdev] [--trigger <Super>z] [--device /dev/input/... ] [--evdev-key z]
  openwispr engine
`)
}
