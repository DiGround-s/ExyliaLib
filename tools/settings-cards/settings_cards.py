#!/usr/bin/env python3
"""Writes a plugin's settings screens (a hub and one page per module) from a short JSON spec.

    python3 settings_cards.py <spec.json> <lang/en/menus/admin dir>

The output is ordinary admin menu YAML in the house card format (CLAUDE.md, "Item cards"), read by
ConfigEdits (docs/config-menus.md): each card's value, default and click lines are placeholders the
library fills in the server's language, so only the label and the explanation need writing. Lines of
the explanation are kept as written: keep them about 34 visible characters. Translate the result into
lang/<code>/ with a worker; the layout (slots, actions, placeholders) must stay identical.

Spec (see ExyliaMarket's tools/settings-spec.json):
{
  "namespace": "exyliamarket",          action namespace
  "menu": "menus/admin/settings",       the hub's id; pages are <menu>_<page id>
  "title": "MARKET",                    the hub title's section word
  "back": {"label": "Market admin", "action": "exyliamarket:admin_hub"},
  "hub": {"about": [...]},
  "pages": [{"id": "ah", "title": "Auction house", "context": "Auction house", "icon": "BARREL",
             "module": true,                     the page shows %<id>_state%
             "comment": [...], "about": [...],
             "hub": {"about": [...], "stats": [["gold", "Fee", "settings.listing-fee-percent"], ...]},
             "cards": [["settings.listing-fee-percent", "Listing fee", "PAPER", "gold", "Fee", ["line", ...]], ...]}]
}
A hub stat whose key starts with % is a placeholder of the plugin's own, written as is
(ExyliaMarket's modules card shows %settings_pending%, the modules waiting for a restart).
"""
import json
import os
import sys

BACK_HEAD = ('basehead-eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMjIz'
             'ZmI2NzQyOTcxNmIyMWJjNmU4ZTdkNjY5Y2VkZGY2NWIxM2UwNzkwYTVjZTU1YjJlMDc3YjgyZDE5ZTEyNCJ9fX0=')
ROW = {1: [13], 2: [12, 14], 3: [12, 13, 14], 4: [11, 12, 14, 15], 5: [11, 12, 13, 14, 15],
       6: [10, 11, 12, 14, 15, 16], 7: [10, 11, 12, 13, 14, 15, 16]}


def q(text):
    return '"' + text.replace('\\', '\\\\').replace('"', '\\"') + '"'


def name_of(page, key):
    return page + '_' + key.replace('.', '_').replace('-', '_')


def slots(count):
    """27 with up to 7 cards in row 1; 36 with 8-14 split evenly over rows 1-2 (CLAUDE.md, "Menu layouts")."""
    if count <= 7:
        return 27, ROW[count]
    if count > 14:
        raise SystemExit(f'{count} cards: split the page, at most 14')
    top = (count + 1) // 2
    return 36, ROW[top] + [s + 9 for s in ROW[count - top]]


def preamble(comment, title, size):
    return ['# DO NOT EDIT — REGENERATED ON EVERY STARTUP.', '#'] + ['# ' + c for c in comment] + [
        '', 'title: ' + q(title), 'size: ' + str(size), 'type: SIMPLE', '',
        'open_sounds:', '  - "minecraft:block.ender_chest.open|1.0|1.4"',
        'click_sounds:', '  - "minecraft:block.note_block.hat|1.0|1.0"',
        'close_sounds:', '  - "minecraft:block.barrel.close|1.0|0.7"', '',
        'filler:', '  global:', '    material: GRAY_STAINED_GLASS_PANE', '    hide_tooltip: true', '', 'items:']


def header(material, title, kind, about, status=None):
    lines = ['{letters_black}' + kind, '', '{primary}✎ Information'] + [' {letters}' + a for a in about]
    if status:
        lines += ['', '{primary}✦ Status'] + status
    return ['  header:', '    slot: 4', '    material: ' + material,
            '    name: ' + q('<gradient:{primary}:{secondary_light}>' + title + '</gradient>'), '    lore:'] + \
           ['      - ' + q(l) for l in lines]


def back(slot, label, action):
    return ['  back:', '    slot: ' + str(slot), '    sound: "minecraft:item.bundle.remove_one|1.0|0.5"',
            "    material: '" + BACK_HEAD + "'",
            '    name: ' + q('{letters}Back {letters_black}▸ <gradient:{error}:{error_light}>' + label + '</gradient>'),
            '    lore:', '      - "{error}{icon:click} Click to go back"', '    actions:', '      - ' + q(action)]


def card(spec, page, c):
    key, label, material, icon, stat, about = c
    ph = name_of(page['id'], key)
    lines = ['{letters_black}' + page['context'] + ' setting', '', '{primary}✎ Information']
    lines += [' {letters}' + a for a in about]
    lines += ['', '{primary}✦ Status',
              '  {letters_black}· {info}{icon:' + icon + '} {letters}' + stat + '{letters_black}: %' + ph + '%',
              '  {letters_black}· {info}{icon:book} {letters}Default{letters_black}: %' + ph + '_default%',
              '', '%' + ph + '_click%']
    return ['    material: ' + material,
            '    name: ' + q('{letters}' + page['context'] + ' {letters_black}▸ <gradient:{primary}:{secondary_light}>'
                             + label + '</gradient>'),
            '    lore:'] + ['      - ' + q(l) for l in lines] + \
           ['    actions:', '      - ' + q(spec['namespace'] + ':setting ' + page['id'] + ' ' + key)]


def page_yaml(spec, page):
    size, where = slots(len(page['cards']))
    out = preamble(page['comment'], '{primary}&lSETTINGS {letters_black}» {highlight}' + page['title'], size)
    status = ['  {letters_black}· {info}{icon:redstone} {letters}Module{letters_black}: %' + page['id'] + '_state%'] \
        if page.get('module') else None
    out += header(page['icon'], page['title'], page['context'] + ' settings', page['about'], status)
    for slot, c in zip(where, page['cards']):
        out += ['', '  ' + c[0].replace('.', '-') + ':', '    slot: ' + str(slot)] + card(spec, page, c)
    out += [''] + back(size - 5, 'Settings', spec['namespace'] + ':settings_hub')
    return '\n'.join(out) + '\n'


def hub_yaml(spec):
    pages = spec['pages']
    if len(pages) > 12:
        raise SystemExit('more than 12 pages: group them')
    rows = [pages] if len(pages) <= 7 else [pages[:(len(pages) + 1) // 2], pages[(len(pages) + 1) // 2:]]
    size = 27 if len(rows) == 1 else 36
    where = ROW[len(rows[0])] + ([s + 9 for s in ROW[len(rows[1])]] if len(rows) > 1 else [])
    out = preamble(['Settings an admin tunes, one card per module. Everything else stays in the files.'],
                   '{primary}&l' + spec['title'] + ' {letters_black}» {highlight}Settings', size)
    out += header('COMPARATOR', 'Settings', spec['title'].capitalize() + ' settings', spec['hub']['about'])
    for slot, page in zip(where, pages):
        hub = page['hub']
        lines = ['{letters_black}Settings page', '', '{primary}✎ Information'] + [' {letters}' + a for a in hub['about']]
        stats = []
        if page.get('module'):
            stats.append('  {letters_black}· {info}{icon:redstone} {letters}Module{letters_black}: %' + page['id'] + '_state%')
        for icon, stat, key in hub.get('stats', []):
            value = key if key.startswith('%') else '%' + name_of(page['id'], key) + '%'
            stats.append('  {letters_black}· {info}{icon:' + icon + '} {letters}' + stat + '{letters_black}: ' + value)
        if stats:
            lines += ['', '{primary}✦ Status'] + stats
        lines += ['', '{primary}{icon:click} Click to open', '{letters_black}' + hub.get('next', 'Each setting, explained.')]
        out += ['', '  ' + page['id'] + ':', '    slot: ' + str(slot), '    material: ' + page['icon'],
                '    name: ' + q('{letters}Settings {letters_black}▸ <gradient:{primary}:{secondary_light}>'
                                 + page['title'] + '</gradient>'),
                '    lore:'] + ['      - ' + q(l) for l in lines] + \
               ['    actions:', '      - ' + q(spec['namespace'] + ':settings_page ' + page['id'])]
    out += [''] + back(size - 5, spec['back']['label'], spec['back']['action'])
    return '\n'.join(out) + '\n'


def main():
    spec = json.load(open(sys.argv[1], encoding='utf8'))
    out = sys.argv[2]
    base = os.path.basename(spec['menu'])
    with open(os.path.join(out, base + '.yml'), 'w', encoding='utf8') as f:
        f.write(hub_yaml(spec))
    for page in spec['pages']:
        with open(os.path.join(out, base + '_' + page['id'] + '.yml'), 'w', encoding='utf8') as f:
            f.write(page_yaml(spec, page))
    print('wrote', len(spec['pages']) + 1, 'files to', out)


if __name__ == '__main__':
    main()
