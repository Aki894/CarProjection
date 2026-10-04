#!/usr/bin/env python3
"""Full Linux MD test entry point; shares the independently testable AOA transport."""
from aoa_probe import main

if __name__ == '__main__':
    raise SystemExit(main(default_phase='session'))
