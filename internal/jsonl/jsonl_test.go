package jsonl

import (
	"strings"
	"testing"
)

func TestDecodeAccountsHandlesBlankCRLFAndNoFinalNewline(t *testing.T) {
	input := "\r\n" +
		`{"account_id":"a","email":"a@example.com","device_id":null,"ip":"not-an-ip","payment_fingerprint":null,"created_at":"2026-01-01T00:00:00Z"}` +
		"\r\n\r\n" +
		`{"account_id":"b","email":"b@example.com","device_id":null,"ip":"","payment_fingerprint":null,"created_at":"2026-01-02T00:00:00Z"}`
	accounts, err := DecodeAccounts(strings.NewReader(input), "fixture")
	if err != nil {
		t.Fatalf("DecodeAccounts() error = %v", err)
	}
	if len(accounts) != 2 || accounts[0].AccountID != "a" || accounts[1].AccountID != "b" {
		t.Fatalf("DecodeAccounts() = %#v", accounts)
	}
}

func TestDecodeAccountsSupportsRecordBeyondScannerDefault(t *testing.T) {
	longLocal := strings.Repeat("a", 70*1024)
	input := `{"account_id":"long","email":"` + longLocal +
		`@example.com","device_id":null,"ip":"192.0.2.1","payment_fingerprint":null,"created_at":"2026-01-01T00:00:00Z"}`
	accounts, err := DecodeAccounts(strings.NewReader(input), "long-fixture")
	if err != nil {
		t.Fatalf("DecodeAccounts() error = %v", err)
	}
	if len(accounts) != 1 || len(accounts[0].Email) <= 64*1024 {
		t.Fatalf("long record was truncated: email length=%d", len(accounts[0].Email))
	}
}

func TestDecodeAccountsReportsLineNumber(t *testing.T) {
	input := "\n" +
		`{"account_id":"ok","created_at":"2026-01-01T00:00:00Z"}` + "\n" +
		`{"account_id":"","created_at":"2026-01-01T00:00:00Z"}` + "\n"
	_, err := DecodeAccounts(strings.NewReader(input), "accounts.jsonl")
	if err == nil {
		t.Fatal("DecodeAccounts() unexpectedly succeeded")
	}
	if !strings.Contains(err.Error(), "accounts.jsonl:3") ||
		!strings.Contains(err.Error(), "missing account_id") {
		t.Fatalf("DecodeAccounts() error = %q", err)
	}
}
