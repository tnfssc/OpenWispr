//go:build !linux || !cgo

package main

import (
	"context"
	"errors"
)

func runEvdevDaemon(_ context.Context, _ *extensionClient, _ string, _ string) error {
	return errors.New("evdev backend is unavailable in this build (requires linux+cgo)")
}
