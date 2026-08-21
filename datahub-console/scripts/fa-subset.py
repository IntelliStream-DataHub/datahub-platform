#!/usr/bin/env python3
"""Report (or regenerate) the Font Awesome subset embedded at the top of all.css.

The console renders ~45 of Font Awesome 6 Free Solid's 1950 glyphs, so all.css carries a
hand-subset of the upstream stylesheet instead of the whole 81KB of it. This script keeps
that subset honest.

    # which glyphs do the templates/JS/Java reference, and does all.css cover them?
    python3 scripts/fa-subset.py

    # rebuild the glyph rules from an upstream Font Awesome all.css
    python3 scripts/fa-subset.py --from /path/to/fontawesome/css/all.css

The second form prints the replacement glyph line; paste it over the glyph line in
src/main/resources/static/css/all.css (the long `.fa-*:before{content:...}` one).

Exits non-zero when a referenced icon has no rule in all.css, so it can be wired into CI.
"""
import argparse
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MAIN = os.path.join(HERE, '..', 'src', 'main')
ALL_CSS = os.path.normpath(os.path.join(MAIN, 'resources', 'static', 'css', 'all.css'))

# Scanned for `fa-*` tokens. Icon names are always written as literals in this codebase
# (no `'fa-' + name` concatenation), which is what makes a static subset safe.
SOURCE_DIRS = ['resources/templates', 'resources/static/js', 'resources/i18n', 'java']
SOURCE_EXTS = ('.html', '.js', '.java', '.properties')

# Non-glyph Font Awesome classes: base/utility rules, not `.fa-<icon>:before` content rules.
NON_GLYPH = {'fa-solid', 'fa-regular', 'fa-brands', 'fa-classic', 'fa-sharp', 'fa-fw'}


def referenced_icons():
    """Every fa-* token used outside the stylesheets, minus the base/utility classes."""
    found = set()
    for rel in SOURCE_DIRS:
        root = os.path.normpath(os.path.join(MAIN, rel))
        for base, _dirs, files in os.walk(root):
            for f in files:
                if f.endswith(SOURCE_EXTS):
                    path = os.path.join(base, f)
                    with open(path, encoding='utf-8', errors='replace') as fh:
                        found.update(re.findall(r'\bfa-[a-z0-9]+(?:-[a-z0-9]+)*', fh.read()))
    return found - NON_GLYPH


def glyph_rules(css):
    """Parse `sel{content:...}` rules, returning [(selectors, declaration)]."""
    css = re.sub(r'/\*.*?\*/', '', css, flags=re.S)
    return [(m.group(1), m.group(2))
            for m in re.finditer(r'([^{}]+)\{(content:[^{}]*)\}', css)]


def icon_of(selector):
    return selector.strip().split(':')[0].lstrip('.')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--from', dest='upstream', metavar='ALL_CSS',
                    help='upstream Font Awesome all.css to rebuild the subset from')
    args = ap.parse_args()

    used = referenced_icons()

    if args.upstream:
        with open(args.upstream, encoding='utf-8') as fh:
            rules = glyph_rules(fh.read())
        kept = []
        for sel, decl in rules:
            keep = [p for p in sel.split(',') if icon_of(p) in used]
            if keep:
                kept.append(','.join(p.strip() for p in keep) + '{' + decl + '}')
        covered = {icon_of(p) for sel, _ in rules for p in sel.split(',')}
        missing = sorted(i for i in used if i not in covered)
        if missing:
            print('# WARNING: no upstream rule for: ' + ', '.join(missing), file=sys.stderr)
        print(''.join(kept))
        print(f'# {len(kept)} rules, {sum(len(k) for k in kept)} bytes '
              f'(upstream: {len(rules)} rules)', file=sys.stderr)
        return 1 if missing else 0

    with open(ALL_CSS, encoding='utf-8') as fh:
        have = {icon_of(p) for sel, _ in glyph_rules(fh.read()) for p in sel.split(',')}
    missing = sorted(used - have)
    unused = sorted(have - used)

    print(f'referenced by templates/JS/Java : {len(used)}')
    print(f'covered by all.css              : {len(used) - len(missing)}')
    if unused:
        print('\nin all.css but no longer referenced (safe to drop):')
        for i in unused:
            print(f'  .{i}')
    if missing:
        print('\nMISSING - referenced but no rule in all.css (icon renders as a blank box):')
        for i in missing:
            print(f'  .{i}')
        print('\nAdd each rule to the glyph line of all.css, or rerun with --from <upstream all.css>.')
        return 1
    print('\nOK - every referenced icon has a rule.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
