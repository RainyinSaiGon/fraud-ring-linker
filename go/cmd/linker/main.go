package main

import (
	"bufio"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	"accountlinker/internal/clustering"
	"accountlinker/internal/domain"
	"accountlinker/internal/jsonl"
	"accountlinker/internal/streaming"
)

func main() {
	if err := run(os.Args[1:], os.Stdin, os.Stdout, os.Stderr); err != nil {
		fmt.Fprintf(os.Stderr, "linker: %v\n", err)
		os.Exit(1)
	}
}

func run(args []string, stdin io.Reader, stdout, stderr io.Writer) error {
	if len(args) == 0 {
		printUsage(stderr)
		return errors.New("missing command")
	}
	switch args[0] {
	case "link":
		return runLink(args[1:], stderr)
	case "stream":
		return runStream(args[1:], stdin, stdout, stderr)
	case "help", "-h", "--help":
		printUsage(stdout)
		return nil
	default:
		printUsage(stderr)
		return fmt.Errorf("unknown command %q", args[0])
	}
}

func runLink(args []string, stderr io.Writer) error {
	flags := flag.NewFlagSet("link", flag.ContinueOnError)
	flags.SetOutput(stderr)
	accountsPath := flags.String("accounts", "", "accounts JSONL path")
	constraintsPath := flags.String("constraints", "", "constraints JSONL path")
	outputPath := flags.String("output", "", "clusters JSON output path")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments: %v", flags.Args())
	}
	if *accountsPath == "" || *constraintsPath == "" || *outputPath == "" {
		return errors.New("link requires --accounts, --constraints, and --output")
	}

	state, err := buildState(*accountsPath, *constraintsPath)
	if err != nil {
		return err
	}
	file, err := os.Create(*outputPath)
	if err != nil {
		return fmt.Errorf("create output %q: %w", *outputPath, err)
	}
	encoder := json.NewEncoder(file)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(state.Clusters()); err != nil {
		_ = file.Close()
		return fmt.Errorf("write output %q: %w", *outputPath, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("close output %q: %w", *outputPath, err)
	}
	return nil
}

func runStream(args []string, stdin io.Reader, stdout, stderr io.Writer) error {
	flags := flag.NewFlagSet("stream", flag.ContinueOnError)
	flags.SetOutput(stderr)
	accountsPath := flags.String("accounts", "", "initial accounts JSONL path")
	constraintsPath := flags.String("constraints", "", "constraints JSONL path")
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments: %v", flags.Args())
	}
	if *accountsPath == "" || *constraintsPath == "" {
		return errors.New("stream requires --accounts and --constraints")
	}

	state, err := buildState(*accountsPath, *constraintsPath)
	if err != nil {
		return err
	}
	linker := streaming.New(state)
	writer := bufio.NewWriter(stdout)
	encoder := json.NewEncoder(writer)
	return jsonl.ScanAccounts(stdin, "stdin", func(account domain.Account) error {
		assignment, err := linker.Assign(account)
		if err != nil {
			return err
		}
		if err := encoder.Encode(assignment); err != nil {
			return fmt.Errorf("encode stream response: %w", err)
		}
		if err := writer.Flush(); err != nil {
			return fmt.Errorf("flush stream response: %w", err)
		}
		return nil
	})
}

func buildState(accountsPath, constraintsPath string) (*clustering.State, error) {
	accounts, err := jsonl.ReadAccounts(accountsPath)
	if err != nil {
		return nil, err
	}
	records, err := jsonl.ReadConstraints(constraintsPath)
	if err != nil {
		return nil, err
	}
	constraints, err := clustering.NewConstraints(records)
	if err != nil {
		return nil, err
	}
	return clustering.Build(accounts, constraints, clustering.DefaultConfig())
}

func printUsage(writer io.Writer) {
	fmt.Fprintln(writer, "Usage:")
	fmt.Fprintln(writer, "  linker link --accounts <accounts.jsonl> --constraints <constraints.jsonl> --output <clusters.json>")
	fmt.Fprintln(writer, "  linker stream --accounts <accounts.jsonl> --constraints <constraints.jsonl>")
}
