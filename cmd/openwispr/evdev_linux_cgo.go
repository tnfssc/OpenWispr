//go:build linux && cgo

package main

import (
	"context"
	"fmt"
	"log"
	"strings"

	evdev "github.com/gvalkov/golang-evdev"
)

func runEvdevDaemon(ctx context.Context, client *extensionClient, devicePath, keyName string) error {
	keyCode, err := resolveEvdevKey(keyName)
	if err != nil {
		return err
	}

	device, err := evdev.Open(devicePath)
	if err != nil {
		return fmt.Errorf("open evdev device %s: %w", devicePath, err)
	}

	if device.File != nil {
		defer device.File.Close()
		go func() {
			<-ctx.Done()
			_ = device.File.Close()
		}()
	}

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
			case 1:
				if down {
					continue
				}
				down = true
				started, err := client.Start("evdev")
				if err != nil {
					log.Printf("evdev key down but start failed: %v", err)
					continue
				}
				log.Printf("evdev key down start=%t", started)
			case 0:
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
