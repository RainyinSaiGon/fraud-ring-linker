package normalize_test

import (
	"reflect"
	"testing"

	"accountlinker/internal/domain"
	"accountlinker/internal/normalize"
)

func TestAccountCanonicalizesGmailAndGooglemail(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name  string
		email string
	}{
		{
			name:  "gmail",
			email: "  John.Doe+promo@GMAIL.com  ",
		},
		{
			name:  "googlemail alias",
			email: "john.doe+other@googlemail.com",
		},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			features := normalize.Account(domain.Account{Email: test.email})
			if got, want := features.EmailCanonical, "johndoe@gmail.com"; got != want {
				t.Fatalf("EmailCanonical = %q, want %q", got, want)
			}
			if got, want := features.EmailBaseLocal, "johndoe"; got != want {
				t.Fatalf("EmailBaseLocal = %q, want %q", got, want)
			}
			if got, want := features.EmailDomain, "gmail.com"; got != want {
				t.Fatalf("EmailDomain = %q, want %q", got, want)
			}
		})
	}
}

func TestAccountPreservesDotsForNonGmailProvider(t *testing.T) {
	t.Parallel()

	features := normalize.Account(domain.Account{
		Email: "  First.Last+tag@Yahoo.com  ",
	})

	if got, want := features.EmailCanonical, "first.last+tag@yahoo.com"; got != want {
		t.Fatalf("EmailCanonical = %q, want %q", got, want)
	}
	if got, want := features.EmailBaseLocal, "first.last+tag"; got != want {
		t.Fatalf("EmailBaseLocal = %q, want %q", got, want)
	}
}

func TestDeletionSignaturesAreUnicodeSafeAndIncludeDepthZero(t *testing.T) {
	t.Parallel()

	got := normalize.DeletionSignatures("åβ猫", 8)
	want := []string{
		"åβ猫",
		"β猫",
		"å猫",
		"åβ",
	}

	if !reflect.DeepEqual(got, want) {
		t.Fatalf("DeletionSignatures() = %#v, want %#v", got, want)
	}
}

func TestWithinOneEdit(t *testing.T) {
	t.Parallel()

	tests := []struct {
		name       string
		left       string
		right      string
		withinEdit bool
	}{
		{name: "equal", left: "john", right: "john", withinEdit: true},
		{name: "one substitution", left: "john", right: "j0hn", withinEdit: true},
		{name: "one insertion", left: "john", right: "johhn", withinEdit: true},
		{name: "one deletion", left: "john", right: "jon", withinEdit: true},
		{name: "unicode substitution", left: "åβ猫", right: "åδ猫", withinEdit: true},
		{name: "unicode deletion", left: "åβ猫", right: "å猫", withinEdit: true},
		{name: "transposition is two edits", left: "john", right: "jhon", withinEdit: false},
		{name: "two substitutions", left: "john", right: "jo42", withinEdit: false},
		{name: "length differs by two", left: "john", right: "johnny", withinEdit: false},
	}

	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()

			if got := normalize.WithinOneEdit(test.left, test.right); got != test.withinEdit {
				t.Fatalf(
					"WithinOneEdit(%q, %q) = %t, want %t",
					test.left,
					test.right,
					got,
					test.withinEdit,
				)
			}
		})
	}
}
