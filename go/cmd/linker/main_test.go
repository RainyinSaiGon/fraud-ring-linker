package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"accountlinker/internal/domain"
)

func TestRunLinkContract(t *testing.T) {
	directory := t.TempDir()
	accountsPath := filepath.Join(directory, "accounts.jsonl")
	constraintsPath := filepath.Join(directory, "constraints.jsonl")
	outputPath := filepath.Join(directory, "clusters.json")
	writeTestFile(t, accountsPath,
		`{"account_id":"a","email":"one@gmail.com","device_id":null,"ip":"192.0.2.1","payment_fingerprint":"p","created_at":"2026-01-01T00:00:00Z"}`+"\n"+
			`{"account_id":"b","email":"two@gmail.com","device_id":null,"ip":"192.0.2.2","payment_fingerprint":"p","created_at":"2026-01-02T00:00:00Z"}`+"\n",
	)
	writeTestFile(t, constraintsPath, "")

	var stdout, stderr bytes.Buffer
	err := run([]string{
		"link",
		"--output", outputPath,
		"--constraints", constraintsPath,
		"--accounts", accountsPath,
	}, strings.NewReader(""), &stdout, &stderr)
	if err != nil {
		t.Fatalf("run link: %v; stderr=%s", err, stderr.String())
	}
	if stdout.Len() != 0 {
		t.Fatalf("batch wrote unexpected stdout: %q", stdout.String())
	}
	data, err := os.ReadFile(outputPath)
	if err != nil {
		t.Fatal(err)
	}
	var output domain.ClustersOutput
	if err := json.Unmarshal(data, &output); err != nil {
		t.Fatalf("decode output: %v", err)
	}
	if len(output.Clusters) != 1 {
		t.Fatalf("got %d clusters, want 1", len(output.Clusters))
	}
}

func TestRunStreamFlushesBeforeEOF(t *testing.T) {
	directory := t.TempDir()
	accountsPath := filepath.Join(directory, "accounts.jsonl")
	constraintsPath := filepath.Join(directory, "constraints.jsonl")
	writeTestFile(t, accountsPath,
		`{"account_id":"a","email":"person@gmail.com","device_id":null,"ip":"192.0.2.1","payment_fingerprint":"pay","created_at":"2026-01-01T00:00:00Z"}`+"\n",
	)
	writeTestFile(t, constraintsPath, "")

	stdinReader, stdinWriter := io.Pipe()
	stdoutReader, stdoutWriter := io.Pipe()
	var stderr bytes.Buffer
	runDone := make(chan error, 1)
	go func() {
		runDone <- run([]string{
			"stream",
			"--accounts", accountsPath,
			"--constraints", constraintsPath,
		}, stdinReader, stdoutWriter, &stderr)
		_ = stdoutWriter.Close()
	}()

	event := `{"account_id":"b","email":"other@example.com","device_id":null,"ip":"198.51.100.2","payment_fingerprint":"pay","created_at":"2026-01-02T00:00:00Z"}` + "\n"
	if _, err := io.WriteString(stdinWriter, event); err != nil {
		t.Fatal(err)
	}
	lineDone := make(chan string, 1)
	go func() {
		line, _ := bufio.NewReader(stdoutReader).ReadString('\n')
		lineDone <- line
	}()

	select {
	case line := <-lineDone:
		var assignment domain.Assignment
		if err := json.Unmarshal([]byte(line), &assignment); err != nil {
			t.Fatalf("invalid stream response %q: %v", line, err)
		}
		if assignment.AccountID != "b" || assignment.ClusterID != "c1" {
			t.Fatalf("unexpected assignment: %+v", assignment)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stream response was not flushed while stdin remained open")
	}

	_ = stdinWriter.Close()
	select {
	case err := <-runDone:
		if err != nil {
			t.Fatalf("stream returned error: %v; stderr=%s", err, stderr.String())
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stream did not stop at EOF")
	}
}

func TestRunRejectsMissingAndUnknownCommands(t *testing.T) {
	for _, args := range [][]string{nil, {"unknown"}} {
		var stdout, stderr bytes.Buffer
		if err := run(args, strings.NewReader(""), &stdout, &stderr); err == nil {
			t.Fatalf("run(%v) unexpectedly succeeded", args)
		}
		if stderr.Len() == 0 {
			t.Fatalf("run(%v) did not print usage to stderr", args)
		}
	}
}

func writeTestFile(t *testing.T, path, contents string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
}
