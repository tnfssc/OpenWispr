//go:build !linux || !cgo

package main

import (
	"context"
	"errors"
)

// ErrEvdevUnavailable is returned by runEvdevDaemon in builds without the
// linux+cgo evdev backend. Callers may match it with errors.Is to degrade
// gracefully.
var ErrEvdevUnavailable = errors.New("evdev backend is unavailable in this build (requires linux+cgo)")

func runEvdevDaemon(_ context.Context, _ *extensionClient, _ string, _ string) error {
	return ErrEvdevUnavailable
}
