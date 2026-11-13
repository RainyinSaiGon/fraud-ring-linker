// Command evaluate is an offline helper for measuring clusters against a
// labeled truth file. It is deliberately separate from cmd/linker.
package main

import (
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"

	"accountlinker/internal/evaluation"
)

func main() {
	if err := run(os.Args[1:], os.Stdout, os.Stderr); err != nil {
		fmt.Fprintf(os.Stderr, "evaluate: %v\n", err)
		os.Exit(1)
	}
}

func run(args []string, stdout, stderr io.Writer) error {
	flags := flag.NewFlagSet("evaluate", flag.ContinueOnError)
	flags.SetOutput(stderr)
	clustersPath := flags.String("clusters", "", "clusters JSON path")
	truthPath := flags.String("truth", "", "labeled truth JSON path")
	constraintsPath := flags.String(
		"constraints",
		"",
		"optional verified-distinct constraints JSONL path",
	)
	if err := flags.Parse(args); err != nil {
		return err
	}
	if flags.NArg() != 0 {
		return fmt.Errorf("unexpected positional arguments: %v", flags.Args())
	}
	if *clustersPath == "" || *truthPath == "" {
		return errors.New("requires --clusters and --truth")
	}

	metrics, err := evaluation.EvaluateFiles(
		*clustersPath,
		*truthPath,
		*constraintsPath,
	)
	if err != nil {
		return err
	}
	encoder := json.NewEncoder(stdout)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(metrics); err != nil {
		return fmt.Errorf("encode metrics: %w", err)
	}
	return nil
}
