package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"github.com/godbus/dbus/v5/introspect"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

func savedFixture(t *testing.T, e *recorderEngine, name string) string {
	t.Helper()
	path := filepath.Join(e.recoveryDir, name)
	if err := os.WriteFile(path, []byte("recorded speech"), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}
func waitIdle(t *testing.T, e *recorderEngine) {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		_, busy, _ := e.Status()
		if !busy {
			return
		}
		time.Sleep(time.Millisecond)
	}
	t.Fatal("pipeline did not finish")
}
func TestRecoveryFailureRestartAndCurrentSettings(t *testing.T) {
	dir := t.TempDir()
	e := &recorderEngine{ctx: context.Background(), recoveryDir: dir}
	oldest := savedFixture(t, e, "001.wav")
	newer := savedFixture(t, e, "002.wav")
	e.pipeline = func(_ context.Context, path string, cfg pipelineConfig) (string, error) {
		if path != oldest {
			t.Errorf("retried %s rather than oldest", path)
		}
		if cfg.STTGroqModel != "new-model" {
			t.Error("retry did not use current settings")
		}
		if _, err := os.ReadFile(path); err != nil {
			t.Error("audio disappeared before processing", err)
		}
		return "", errors.New("network unavailable")
	}
	token, err := e.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`)
	if err != nil || token == "" {
		t.Fatal(token, err)
	}
	waitIdle(t, e)
	restarted := &recorderEngine{ctx: context.Background(), recoveryDir: dir}
	count, statusErr := restarted.RecoveryStatus()
	if statusErr != nil || count != 2 {
		t.Fatal(count, statusErr)
	}
	restarted.pipeline = func(_ context.Context, path string, cfg pipelineConfig) (string, error) {
		return "recovered result", nil
	}
	if _, err := restarted.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, restarted)
	if _, err := os.Stat(oldest); !os.IsNotExist(err) {
		t.Fatal("successful retry kept audio", err)
	}
	if _, err := os.Stat(newer); err != nil {
		t.Fatal("older retry deleted newer speech", err)
	}
	if deleted, err := restarted.discard(); !deleted || err != nil {
		t.Fatal(deleted, err)
	}
	if _, err := os.Stat(newer); !os.IsNotExist(err) {
		t.Fatal("discard did not delete audio", err)
	}
}
func TestRecoveryCancellationAndBusy(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: t.TempDir()}
	path := savedFixture(t, e, "001.wav")
	started := make(chan struct{})
	e.pipeline = func(ctx context.Context, _ string, _ pipelineConfig) (string, error) {
		close(started)
		<-ctx.Done()
		return "late text", nil
	}
	token, err := e.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`)
	if err != nil {
		t.Fatal(err)
	}
	<-started
	if _, err := e.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`); err == nil {
		t.Fatal("concurrent retry accepted")
	}
	if _, err := e.discard(); err == nil {
		t.Fatal("discard accepted during processing")
	}
	e.mu.Lock()
	cancel := e.cancels[token]
	e.mu.Unlock()
	cancel()
	waitIdle(t, e)
	if _, err := os.Stat(path); err != nil {
		t.Fatal("cancel deleted speech", err)
	}
	if deleted, err := e.discard(); !deleted || err != nil {
		t.Fatal(deleted, err)
	}
}
func TestRecoveryPrivateUniqueAndActiveOwnership(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: filepath.Join(t.TempDir(), "private")}
	first, err := e.createRecording()
	if err != nil {
		t.Fatal(err)
	}
	second, err := e.createRecording()
	if err != nil {
		t.Fatal(err)
	}
	if first == second {
		t.Fatal("recordings overwrite each other")
	}
	if err := os.WriteFile(first, []byte("old speech"), 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(second, []byte("active speech"), 0600); err != nil {
		t.Fatal(err)
	}
	e.outputFile = second
	e.recording = true
	count, _ := e.RecoveryStatus()
	if count != 1 {
		t.Fatal("active audio offered for recovery", count)
	}
	if _, err := e.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`); err == nil {
		t.Fatal("retry accepted while recording")
	}
	if _, err := e.discard(); err == nil {
		t.Fatal("discard accepted while recording")
	}
	for path, mode := range map[string]os.FileMode{e.recoveryDir: 0700, first: 0600, second: 0600} {
		info, err := os.Stat(path)
		if err != nil || info.Mode().Perm() != mode {
			t.Fatal(path, info, err)
		}
	}
}
func TestRecoveryInvalidConfigAndEmptyStore(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: t.TempDir()}
	if token, err := e.retry(`{"sttProvider":"groq","sttGroqModel":"new-model"}`); token != "" || err != nil {
		t.Fatal(token, err)
	}
	if deleted, err := e.discard(); deleted || err != nil {
		t.Fatal(deleted, err)
	}
	path := savedFixture(t, e, "001.wav")
	if _, err := e.retry("invalid json"); err == nil {
		t.Fatal("invalid config accepted")
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal("invalid config lost audio", err)
	}
}

func TestAsyncStopOwnsAudioUntilPipelineFinishes(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: t.TempDir(), cancels: map[string]context.CancelFunc{}}
	path := savedFixture(t, e, "001.wav")
	cmd := testRecorder(t)
	e.recordCmd = cmd
	e.outputFile = path
	e.recording = true
	started := make(chan struct{})
	release := make(chan struct{})
	e.pipeline = func(_ context.Context, input string, _ pipelineConfig) (string, error) {
		close(started)
		<-release
		if input != path {
			t.Error("wrong recording")
		}
		if _, err := os.ReadFile(input); err != nil {
			t.Error("async Stop removed its goroutine's input", err)
		}
		return "", errors.New("provider unavailable")
	}
	token, err := e.stop(true, `{"sttProvider":"groq"}`)
	if err != nil || token == "" {
		t.Fatal(token, err)
	}
	<-started
	if _, err := os.Stat(path); err != nil {
		t.Fatal("Stop removed audio before pipeline returned", err)
	}
	close(release)
	waitIdle(t, e)
	if count, _ := e.RecoveryStatus(); count != 1 {
		t.Fatal("failed Stop lost recording", count)
	}
}
func TestStopWithoutTranscriptionDeletesRecording(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: t.TempDir(), cancels: map[string]context.CancelFunc{}}
	path := savedFixture(t, e, "001.wav")
	cmd := testRecorder(t)
	e.recordCmd = cmd
	e.outputFile = path
	e.recording = true
	if token, err := e.stop(false, ""); token == "" || err != nil {
		t.Fatal(token, err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("abandoned recording kept audio", err)
	}
}

func testRecorder(t *testing.T) *exec.Cmd {
	t.Helper()
	cmd := exec.Command("sh", "-c", "trap 'exit 0' INT; echo ready; while :; do :; done")
	out, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	if _, err := bufio.NewReader(out).ReadString('\n'); err != nil {
		t.Fatal(err)
	}
	return cmd
}

func TestHTTPFailureThenRetryUsesRetainedAudioAndNewKey(t *testing.T) {
	e := &recorderEngine{ctx: context.Background(), recoveryDir: t.TempDir(), cancels: map[string]context.CancelFunc{}}
	path := savedFixture(t, e, "001.wav")
	currentKey := "old-key"
	originalRead := readSecret
	readSecret = func(string) (string, error) { return currentKey, nil }
	defer func() { readSecret = originalRead }()
	fail := true
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		file, _, err := r.FormFile("file")
		if err != nil {
			t.Error(err)
			w.WriteHeader(400)
			return
		}
		defer file.Close()
		data, err := io.ReadAll(file)
		if err != nil || string(data) != "recorded speech" {
			t.Error("original audio did not reach provider", string(data), err)
		}
		if fail {
			w.WriteHeader(503)
			return
		}
		if r.Header.Get("Authorization") != "Bearer new-key" {
			t.Error("retry used stale credentials")
		}
		w.Header().Set("Content-Type", "application/json")
		io.WriteString(w, `{"text":"Recovered speech"}`)
	}))
	defer server.Close()
	cfg, _ := json.Marshal(pipelineConfig{STTProvider: "groq", STTGroqEndpoint: server.URL, STTGroqModel: "current-model"})
	e.recordCmd = testRecorder(t)
	e.outputFile = path
	e.recording = true
	if _, err := e.stop(true, string(cfg)); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e)
	if _, err := os.Stat(path); err != nil {
		t.Fatal("HTTP failure lost speech", err)
	}
	fail = false
	currentKey = "new-key"
	if _, err := e.retry(string(cfg)); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e)
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("successful HTTP retry kept audio", err)
	}
}
func TestRecorderDBusSignatures(t *testing.T) {
	methods := introspect.Methods(&recorderEngine{})
	expected := map[string]string{"Start": "b", "Stop": "bss", "Cancel": "s", "Status": "bb", "Retry": "ss", "Discard": "b", "RecoveryStatus": "u"}
	for _, method := range methods {
		if want, ok := expected[method.Name]; ok {
			signature := ""
			for _, arg := range method.Args {
				signature += arg.Type
			}
			if signature != want {
				t.Errorf("%s: got %s, want %s", method.Name, signature, want)
			}
			delete(expected, method.Name)
		}
	}
	if len(expected) > 0 {
		t.Fatal("missing D-Bus methods", expected)
	}
}
