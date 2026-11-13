package normalize

import "unicode/utf8"

// DeletionSignatures emits the original local part plus each one-rune
// deletion. Output is deduplicated and bounded by maxRunes.
func DeletionSignatures(local string, maxRunes int) []string {
	if local == "" || maxRunes <= 0 || utf8.RuneCountInString(local) > maxRunes {
		return nil
	}
	runes := []rune(local)
	result := make([]string, 0, len(runes)+1)
	seen := make(map[string]struct{}, len(runes)+1)
	add := func(value string) {
		if _, exists := seen[value]; exists {
			return
		}
		seen[value] = struct{}{}
		result = append(result, value)
	}
	add(local)
	for i := range runes {
		candidate := string(append(append([]rune(nil), runes[:i]...), runes[i+1:]...))
		add(candidate)
	}
	return result
}

// WithinOneEdit reports whether two strings have Levenshtein distance <= 1.
// It deliberately does not treat a transposition as one edit.
func WithinOneEdit(left, right string) bool {
	a := []rune(left)
	b := []rune(right)
	if len(a)-len(b) > 1 || len(b)-len(a) > 1 {
		return false
	}
	if len(a) > len(b) {
		a, b = b, a
	}
	if len(a) == len(b) {
		differences := 0
		for i := range a {
			if a[i] != b[i] {
				differences++
				if differences > 1 {
					return false
				}
			}
		}
		return true
	}

	i, j := 0, 0
	skipped := false
	for i < len(a) && j < len(b) {
		if a[i] == b[j] {
			i++
			j++
			continue
		}
		if skipped {
			return false
		}
		skipped = true
		j++
	}
	return true
}
