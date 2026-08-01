//go:build linux && cgo

package main

import (
	"context"
	"fmt"
	"log"
	"strings"
	"sync"

	evdev "github.com/gvalkov/golang-evdev"
)

// keyValDown and keyValUp are the values reported by an evdev EV_KEY event
// when a key transitions to pressed or released, respectively.
const (
	keyValDown int32 = 1
	keyValUp   int32 = 0
)

// runEvdevDaemon opens devicePath as an evdev input device, grabs it so
// trigger key events do not propagate to the focused window, and dispatches
// Start/Stop calls on client keyed on keyName. It blocks until ctx is
// cancelled or a fatal read error occurs.
func runEvdevDaemon(ctx context.Context, client *extensionClient, devicePath, keyName string) error {
	keyCode, err := resolveEvdevKey(keyName)
	if err != nil {
		return fmt.Errorf("resolve evdev key %q: %w", keyName, err)
	}

	device, err := evdev.Open(devicePath)
	if err != nil {
		return fmt.Errorf("open evdev device %s: %w", devicePath, err)
	}

	// Grab the device so trigger key events are not delivered to the focused
	// window. On grab failure close the device file and surface the error.
	if err := device.Grab(); err != nil {
		_ = device.File.Close()
		return fmt.Errorf("grab evdev device %s: %w", devicePath, err)
	}
	defer device.Release()

	// closeFile guards against the double-close race between the deferred
	// close and the goroutine that closes the fd on context cancellation.
	var closeOnce sync.Once
	closeFile := func() { closeOnce.Do(func() { _ = device.File.Close() }) }
	defer closeFile()

	// The closer goroutine must not outlive this function: stop is closed on
	// every return path, so the goroutine exits even on non-ctx errors.
	stop := make(chan struct{})
	defer close(stop)
	go func() {
		select {
		case <-ctx.Done():
		case <-stop:
		}
		closeFile()
	}()

	log.Printf("evdev backend listening on %s keycode=%d", devicePath, keyCode)

	var down bool
	for {
		events, err := device.Read()
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("read evdev events: %w", err)
		}

		for _, event := range events {
			if event.Type != evdev.EV_KEY || event.Code != keyCode {
				continue
			}

			switch event.Value {
			case keyValDown:
				if down {
					continue
				}
				started, err := client.Start("evdev")
				if err != nil {
					log.Printf("evdev key down but start failed: %v", err)
					continue
				}
				// Only mark down after a successful Start, so a failed
				// Start does not pair with a spurious key-up Stop.
				down = true
				log.Printf("evdev key down start=%t", started)
			case keyValUp:
				if !down {
					continue
				}
				down = false
				stopped, err := client.Stop(true, "evdev")
				if err != nil {
					log.Printf("evdev key up but stop failed: %v", err)
					continue
				}
				log.Printf("evdev key up stop=%t", stopped)
			}
		}
	}
}

// resolveEvdevKey maps a human-friendly key name to its evdev KEY_* code.
func resolveEvdevKey(name string) (uint16, error) {
	switch strings.ToLower(name) {
	case "z", "key_z":
		return evdev.KEY_Z, nil
	case "capslock", "caps_lock", "caps":
		return evdev.KEY_CAPSLOCK, nil
	case "rightalt", "alt_r", "ralt", "altgr":
		return evdev.KEY_RIGHTALT, nil
	case "leftalt", "alt_l", "lalt":
		return evdev.KEY_LEFTALT, nil
	default:
		return 0, fmt.Errorf("unsupported evdev key: %s", name)
	}
}
