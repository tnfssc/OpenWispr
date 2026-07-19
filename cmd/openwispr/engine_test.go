package main

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestParseLLMJSONResponsesOutput(t *testing.T) {
	raw := []byte(`{"output":[{"content":[{"type":"output_text","text":" hello from responses "}]}]}`)
	got := parseLLMJSON(raw)
	if got != "hello from responses" {
		t.Fatalf("unexpected parse result: %q", got)
	}
}

func TestParseLLMJSONChatCompletions(t *testing.T) {
	raw := []byte(`{"choices":[{"message":{"content":" cleaned text "}}]}`)
	got := parseLLMJSON(raw)
	if got != "cleaned text" {
		t.Fatalf("unexpected parse result: %q", got)
	}
}

func TestNormalizeCleanedTranscript(t *testing.T) {
	tests := []struct {
		name string
		in   string
		want string
	}{
		{
			name: "code fence",
			in:   "```markdown\nHello world\n```",
			want: "Hello world",
		},
		{
			name: "quoted output",
			in:   "\"Hello world\"",
			want: "Hello world",
		},
		{
			name: "json text payload",
			in:   `{"text":"Hello world"}`,
			want: "Hello world",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := normalizeCleanedTranscript(tt.in)
			if got != tt.want {
				t.Fatalf("normalize mismatch: got=%q want=%q", got, tt.want)
			}
		})
	}
}

func TestCleanupTranscriptOpenAIRequest(t *testing.T) {
	var capturedAuth string
	var capturedModel string
	var capturedPrompt string
	var capturedUser string

	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer r.Body.Close()

		capturedAuth = r.Header.Get("Authorization")
		body, _ := io.ReadAll(r.Body)

		var payload map[string]any
		if err := json.Unmarshal(body, &payload); err != nil {
			t.Fatalf("invalid JSON payload: %v", err)
		}

		capturedModel, _ = payload["model"].(string)
		msgs, _ := payload["messages"].([]any)
		if len(msgs) >= 2 {
			first, _ := msgs[0].(map[string]any)
			second, _ := msgs[1].(map[string]any)
			capturedPrompt, _ = first["content"].(string)
			capturedUser, _ = second["content"].(string)
		}

		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte("{\"choices\":[{\"message\":{\"content\":\"```\\nCleaned output\\n```\"}}]}"))
	}))
	defer srv.Close()

	cfg := pipelineConfig{
		LLMProvider:       "openai",
		LLMOpenAIEndpoint: srv.URL,
		LLMOpenAIModel:    "gpt-test",
		LLMOpenAIApiKey:   "test-key",
		LLMCleanupPrompt:  "test prompt",
	}

	got, err := cleanupTranscript(context.Background(), "raw transcript", cfg)
	if err != nil {
		t.Fatalf("cleanupTranscript returned error: %v", err)
	}

	if got != "Cleaned output" {
		t.Fatalf("unexpected cleaned output: %q", got)
	}
	if capturedAuth != "Bearer test-key" {
		t.Fatalf("unexpected auth header: %q", capturedAuth)
	}
	if capturedModel != "gpt-test" {
		t.Fatalf("unexpected model: %q", capturedModel)
	}
	if capturedPrompt != "test prompt" {
		t.Fatalf("unexpected prompt: %q", capturedPrompt)
	}
	wantUser := buildCleanupUserMessage("raw transcript")
	if capturedUser != wantUser {
		t.Fatalf("unexpected user content: %q", capturedUser)
	}
}

func TestBuildCleanupUserMessage(t *testing.T) {
	got := buildCleanupUserMessage("  hello world  ")
	if !strings.Contains(got, "<transcript>") || !strings.Contains(got, "</transcript>") {
		t.Fatalf("missing transcript delimiters: %q", got)
	}
	if !strings.Contains(got, "hello world") {
		t.Fatalf("transcript text missing: %q", got)
	}
}

func TestLooksLikeHallucinatedCleanup(t *testing.T) {
	tests := []struct {
		name     string
		original string
		cleaned  string
		want     bool
	}{
		{
			name:     "normal cleanup",
			original: "uh okay we should restart portal then run doctor",
			cleaned:  "Okay, we should restart the portal, then run doctor.",
			want:     false,
		},
		{
			name:     "assistant style hallucination",
			original: "can you tell me what the weather is tomorrow",
			cleaned:  "Certainly! The weather tomorrow will be sunny with highs of 28C.",
			want:     true,
		},
		{
			name:     "low overlap rewrite",
			original: "we had a deployment issue and need to restart the service",
			cleaned:  "Let's discuss best practices for reliability engineering and future planning.",
			want:     true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := looksLikeHallucinatedCleanup(tt.original, tt.cleaned)
			if got != tt.want {
				t.Fatalf("hallucination mismatch: got=%t want=%t", got, tt.want)
			}
		})
	}
}

func TestCleanupTranscriptGroqRoutingAndFailure(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.Contains(r.URL.Path, "/groq") {
			t.Fatalf("unexpected path: %s", r.URL.Path)
		}
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = w.Write([]byte(`{"error":"bad"}`))
	}))
	defer srv.Close()

	cfg := pipelineConfig{
		LLMProvider:      "groq",
		LLMGroqEndpoint:  srv.URL + "/groq",
		LLMGroqModel:     "llama-test",
		LLMGroqApiKey:    "groq-key",
		LLMCleanupPrompt: "prompt",
	}

	original := "leave this as fallback"
	got, err := cleanupTranscript(context.Background(), original, cfg)
	if err == nil {
		t.Fatal("expected cleanupTranscript to fail on HTTP 500")
	}
	if got != original {
		t.Fatalf("expected fallback text on failure, got=%q", got)
	}
}
