package normalize

import (
	"net/netip"
	"strings"
	"unicode"

	"accountlinker/internal/domain"
)

// Account normalizes an account once for use by batch and streaming paths.
// Malformed optional fraud signals are treated as unavailable.
func Account(account domain.Account) domain.Features {
	exact, canonical, local, baseLocal, skeleton, emailDomain := email(account.Email)

	var device, payment string
	if account.DeviceID != nil {
		device = strings.TrimSpace(*account.DeviceID)
	}
	if account.PaymentFingerprint != nil {
		payment = strings.TrimSpace(*account.PaymentFingerprint)
	}

	var addr netip.Addr
	var prefix netip.Prefix
	if parsed, err := netip.ParseAddr(strings.TrimSpace(account.IP)); err == nil {
		addr = parsed.Unmap()
		bits := 64
		if addr.Is4() {
			bits = 24
		}
		prefix = netip.PrefixFrom(addr, bits).Masked()
	}

	return domain.Features{
		EmailExact:     exact,
		EmailCanonical: canonical,
		EmailLocal:     local,
		EmailBaseLocal: baseLocal,
		EmailSkeleton:  skeleton,
		EmailDomain:    emailDomain,
		DeviceID:       device,
		Payment:        payment,
		IP:             addr,
		IPPrefix:       prefix,
		CreatedAt:      account.CreatedAt,
	}
}

func email(raw string) (exact, canonical, local, baseLocal, skeleton, domainName string) {
	value := strings.ToLower(strings.TrimSpace(raw))
	at := strings.LastIndexByte(value, '@')
	if at <= 0 || at == len(value)-1 {
		return value, value, value, value, emailSkeleton(value), ""
	}

	local = value[:at]
	domainName = strings.TrimSpace(value[at+1:])
	exact = local + "@" + domainName
	baseLocal = local

	switch domainName {
	case "googlemail.com":
		domainName = "gmail.com"
		fallthrough
	case "gmail.com":
		baseLocal = stripPlus(baseLocal)
		baseLocal = strings.ReplaceAll(baseLocal, ".", "")
	case "outlook.com", "hotmail.com", "live.com", "proton.me", "protonmail.com":
		baseLocal = stripPlus(baseLocal)
	}

	canonical = baseLocal + "@" + domainName
	skeleton = emailSkeleton(baseLocal)
	return exact, canonical, local, baseLocal, skeleton, domainName
}

func stripPlus(local string) string {
	if plus := strings.IndexByte(local, '+'); plus >= 0 {
		return local[:plus]
	}
	return local
}

// emailSkeleton is comparison-only evidence. It is never used as an exact
// identity key and only becomes merge-eligible with an independent signal.
func emailSkeleton(local string) string {
	var builder strings.Builder
	for _, r := range local {
		if r == '.' || r == '_' || r == '-' || unicode.IsSpace(r) {
			continue
		}
		builder.WriteRune(r)
	}
	return builder.String()
}
