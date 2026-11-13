package jsonl

import (
	"bufio"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"strings"

	"accountlinker/internal/domain"
)

const maxJSONLRecordSize = 4 * 1024 * 1024

func ReadAccounts(path string) ([]domain.Account, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open accounts %q: %w", path, err)
	}
	defer file.Close()
	return DecodeAccounts(file, path)
}

func DecodeAccounts(reader io.Reader, source string) ([]domain.Account, error) {
	var accounts []domain.Account
	err := scan(reader, source, func(line []byte, lineNumber int) error {
		var account domain.Account
		if err := json.Unmarshal(line, &account); err != nil {
			return fmt.Errorf("decode account: %w", err)
		}
		account.AccountID = strings.TrimSpace(account.AccountID)
		if account.AccountID == "" {
			return fmt.Errorf("missing account_id")
		}
		if account.CreatedAt.IsZero() {
			return fmt.Errorf("missing or invalid created_at")
		}
		accounts = append(accounts, account)
		return nil
	})
	return accounts, err
}

func ReadConstraints(path string) ([]domain.Constraint, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open constraints %q: %w", path, err)
	}
	defer file.Close()
	return DecodeConstraints(file, path)
}

func DecodeConstraints(reader io.Reader, source string) ([]domain.Constraint, error) {
	var constraints []domain.Constraint
	err := scan(reader, source, func(line []byte, lineNumber int) error {
		var constraint domain.Constraint
		if err := json.Unmarshal(line, &constraint); err != nil {
			return fmt.Errorf("decode constraint: %w", err)
		}
		constraint.AccountA = strings.TrimSpace(constraint.AccountA)
		constraint.AccountB = strings.TrimSpace(constraint.AccountB)
		constraint.Relation = strings.TrimSpace(constraint.Relation)
		if constraint.AccountA == "" || constraint.AccountB == "" {
			return fmt.Errorf("constraint endpoint is empty")
		}
		constraints = append(constraints, constraint)
		return nil
	})
	return constraints, err
}

// ScanAccounts processes streaming records without imposing Scanner's small
// default token limit. Blank lines are ignored consistently with file input.
func ScanAccounts(reader io.Reader, source string, handle func(domain.Account) error) error {
	return scan(reader, source, func(line []byte, lineNumber int) error {
		var account domain.Account
		if err := json.Unmarshal(line, &account); err != nil {
			return fmt.Errorf("decode account: %w", err)
		}
		account.AccountID = strings.TrimSpace(account.AccountID)
		if account.AccountID == "" {
			return fmt.Errorf("missing account_id")
		}
		if account.CreatedAt.IsZero() {
			return fmt.Errorf("missing or invalid created_at")
		}
		return handle(account)
	})
}

func scan(reader io.Reader, source string, handle func([]byte, int) error) error {
	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 64*1024), maxJSONLRecordSize)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := scanner.Bytes()
		if len(strings.TrimSpace(string(line))) == 0 {
			continue
		}
		if err := handle(line, lineNumber); err != nil {
			return fmt.Errorf("%s:%d: %w", source, lineNumber, err)
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("read %s near line %d: %w", source, lineNumber+1, err)
	}
	return nil
}
