package index_test

import (
	"fmt"
	"testing"

	"accountlinker/internal/domain"
	"accountlinker/internal/index"
	"accountlinker/internal/normalize"
)

func TestCandidatePairsSuppressHotEmailPosting(t *testing.T) {
	t.Parallel()

	config := index.DefaultConfig()
	config.EmailBlockLimit = 2
	config.SignatureBlockLimit = 2

	store := index.New(config)
	for i, email := range []string{
		"john.doe@gmail.com",
		"johndoe+one@gmail.com",
		"j.o.h.n.d.o.e+two@googlemail.com",
	} {
		account := domain.Account{
			AccountID: fmt.Sprintf("acc_%d", i),
			Email:     email,
		}
		if _, err := store.Add(account, normalize.Account(account)); err != nil {
			t.Fatalf("Add(%q): %v", account.AccountID, err)
		}
	}

	if got := store.CandidatePairs(); len(got) != 0 {
		t.Fatalf("CandidatePairs() returned %d pairs from hot email postings, want 0: %#v", len(got), got)
	}
}

func TestCandidatesForUsesProjectedSizeForHotNetworkPosting(t *testing.T) {
	t.Parallel()

	config := index.DefaultConfig()
	config.IPBlockLimit = 2
	config.PrefixBlockLimit = 2

	store := index.New(config)
	for i, email := range []string{
		"one@one.example",
		"two@two.example",
	} {
		account := domain.Account{
			AccountID: fmt.Sprintf("acc_%d", i),
			Email:     email,
			IP:        "203.0.113.25",
		}
		if _, err := store.Add(account, normalize.Account(account)); err != nil {
			t.Fatalf("Add(%q): %v", account.AccountID, err)
		}
	}

	incoming := normalize.Account(domain.Account{
		AccountID: "acc_incoming",
		Email:     "three@three.example",
		IP:        "203.0.113.25",
	})

	if got := store.CandidatesFor(incoming, false); len(got) != 2 {
		t.Fatalf("CandidatesFor(projected=false) returned %d candidates, want 2: %#v", len(got), got)
	}
	if got := store.CandidatesFor(incoming, true); len(got) != 0 {
		t.Fatalf("CandidatesFor(projected=true) returned %d candidates from a projected hot posting, want 0: %#v", len(got), got)
	}
}

func TestCandidatePairsSuppressHotNetworkPosting(t *testing.T) {
	t.Parallel()

	config := index.DefaultConfig()
	config.IPBlockLimit = 2
	config.PrefixBlockLimit = 2

	store := index.New(config)
	for i := 0; i < 3; i++ {
		account := domain.Account{
			AccountID: fmt.Sprintf("acc_%d", i),
			Email:     fmt.Sprintf("person@domain-%d.example", i),
			IP:        "198.51.100.10",
		}
		if _, err := store.Add(account, normalize.Account(account)); err != nil {
			t.Fatalf("Add(%q): %v", account.AccountID, err)
		}
	}

	if got := store.CandidatePairs(); len(got) != 0 {
		t.Fatalf("CandidatePairs() returned %d pairs from hot network postings, want 0: %#v", len(got), got)
	}
}
