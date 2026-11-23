import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const conf = readFileSync("nginx.conf", "utf8");
const headers = readFileSync("nginx-security-headers.conf", "utf8");

// nginx drops inherited add_header directives as soon as a location defines its own.
describe("nginx config", () => {
  it("repeats the security headers in every location that sets its own headers", () => {
    const locations = [...conf.matchAll(/location [^{]+\{([^}]*)\}/g)].map((m) => m[1]!);
    expect(locations.length).toBeGreaterThan(1);
    for (const body of locations) expect(body).toContain("security-headers.conf");
  });
  it("revalidates index.html but caches hashed assets immutably", () => {
    expect(conf).toMatch(/location \/ \{[^}]*Cache-Control "no-cache"/);
    expect(conf).toMatch(/location \/assets\/ \{[^}]*immutable/);
  });
  it("forbids framing, plugins and base-tag injection in the CSP", () => {
    for (const directive of ["frame-ancestors 'none'", "object-src 'none'", "base-uri 'self'"]) expect(headers).toContain(directive);
  });
});
