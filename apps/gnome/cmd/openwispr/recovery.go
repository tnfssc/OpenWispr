package main

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"

	"github.com/godbus/dbus/v5"
)

// Record directly into user-private persistent storage. Even an engine restart
// during a request must not turn spoken work into an unowned temp artifact.
func (e *recorderEngine) audioDirectory() (string, error) {
	if e.recoveryDir != "" {
		return e.recoveryDir, nil
	}
	base := os.Getenv("XDG_STATE_HOME")
	if base == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		base = filepath.Join(home, ".local", "state")
	}
	return filepath.Join(base, "openwispr", "failed-audio"), nil
}
func (e *recorderEngine) createRecording() (string, error) {
	dir, err := e.audioDirectory()
	if err != nil {
		return "", err
	}
	if err = os.MkdirAll(dir, 0700); err != nil {
		return "", err
	}
	if err = os.Chmod(dir, 0700); err != nil {
		return "", err
	}
	f, err := os.CreateTemp(dir, fmt.Sprintf("%020d-*.wav", time.Now().UnixNano()))
	if err != nil {
		return "", err
	}
	path := f.Name()
	err = f.Close()
	return path, err
}

// Caller holds mu. Only inactive files are offered for recovery, oldest first.
func (e *recorderEngine) savedAudio() ([]string, error) {
	dir, err := e.audioDirectory()
	if err != nil {
		return nil, err
	}
	entries, err := os.ReadDir(dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var paths []string
	for _, entry := range entries {
		path := filepath.Join(dir, entry.Name())
		if entry.Type().IsRegular() && strings.HasSuffix(entry.Name(), ".wav") && path != e.outputFile {
			info, err := entry.Info()
			if err != nil {
				return nil, err
			}
			if info.Size() > 0 {
				paths = append(paths, path)
			}
		}
	}
	sort.Strings(paths)
	return paths, nil
}
func (e *recorderEngine) RecoveryStatus() (uint32, *dbus.Error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	paths, err := e.savedAudio()
	if err != nil {
		return 0, dbus.MakeFailedError(err)
	}
	return uint32(len(paths)), nil
}
func (e *recorderEngine) Retry(msg dbus.Message, configJSON string) (string, *dbus.Error) {
	if err := e.authorize(msg); err != nil {
		return "", dbus.MakeFailedError(err)
	}
	return e.retry(configJSON)
}
func (e *recorderEngine) retry(configJSON string) (string, *dbus.Error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.recording || e.processing {
		return "", dbus.MakeFailedError(errors.New("finish or cancel the current dictation first"))
	}
	paths, err := e.savedAudio()
	if err != nil {
		return "", dbus.MakeFailedError(err)
	}
	if len(paths) == 0 {
		return "", nil
	}
	cfg, err := parsePipelineConfig(configJSON)
	if err != nil {
		return "", dbus.MakeFailedError(err)
	}
	token := fmt.Sprintf("%d", time.Now().UnixNano())
	ctx, cancel := context.WithCancel(e.ctx)
	if e.cancels == nil {
		e.cancels = map[string]context.CancelFunc{}
	}
	e.cancels[token] = cancel
	e.processing = true
	e.process(ctx, cancel, token, paths[0], cfg)
	return token, nil
}
func (e *recorderEngine) Discard(msg dbus.Message) (bool, *dbus.Error) {
	if err := e.authorize(msg); err != nil {
		return false, dbus.MakeFailedError(err)
	}
	return e.discard()
}
func (e *recorderEngine) discard() (bool, *dbus.Error) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.recording || e.processing {
		return false, dbus.MakeFailedError(errors.New("finish or cancel the current dictation first"))
	}
	paths, err := e.savedAudio()
	if err != nil {
		return false, dbus.MakeFailedError(err)
	}
	if len(paths) == 0 {
		return false, nil
	}
	if err = os.Remove(paths[0]); err != nil {
		return false, dbus.MakeFailedError(err)
	}
	return true, nil
}
func (e *recorderEngine) process(ctx context.Context, cancel context.CancelFunc, token, path string, cfg pipelineConfig) {
	go func() {
		defer cancel()
		pipeline := e.pipeline
		if pipeline == nil {
			pipeline = runPipeline
		}
		text, err := pipeline(ctx, path, cfg)
		// A cancelled request retains the audio even if the provider races cancellation.
		if ctx.Err() != nil {
			err = ctx.Err()
		}
		if err == nil {
			err = os.Remove(path)
			if err != nil {
				err = fmt.Errorf("could not delete processed audio: %w", err)
			}
		}
		message := ""
		if err != nil {
			message = err.Error()
		}
		e.finishProcessing()
		e.removeCancel(token)
		e.emitCompletion(token, text, message)
	}()
}
