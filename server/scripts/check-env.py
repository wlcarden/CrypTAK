#!/usr/bin/env python3
"""
Pre-start environment validation.
Refuses to launch if any changeme_ sentinel values remain in .env.
Usage: python3 check-env.py [--env /path/to/.env]
"""
import sys, re, argparse

parser = argparse.ArgumentParser()
parser.add_argument("--env", default=".env", help="Path to .env file")
args = parser.parse_args()

try:
    with open(args.env) as f:
        lines = f.readlines()
except FileNotFoundError:
    print(f"ERROR: {args.env} not found. Copy .env.example to .env and configure it.")
    sys.exit(1)

errors = []
for i, line in enumerate(lines, 1):
    line = line.strip()
    if line.startswith("#") or "=" not in line:
        continue
    key, _, val = line.partition("=")
    if "changeme" in val.lower():
        errors.append(f"  Line {i}: {key}= (contains 'changeme')")
    if key == "NR_ADMIN_PASS" and not val.strip():
        errors.append(f"  Line {i}: NR_ADMIN_PASS is empty — Node-RED editor has no auth")

if errors:
    print("ERROR: .env contains unconfigured sentinel values:")
    for e in errors:
        print(e)
    print("\nCopy .env.example, fill in all values, and retry.")
    sys.exit(1)

print("OK: .env validation passed")
