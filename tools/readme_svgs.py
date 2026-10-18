"""Generates the README's animated SVGs in light and dark variants: python3 tools/readme_svgs.py"""
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "docs" / "assets"
FONT = "-apple-system, BlinkMacSystemFont, 'Segoe UI', Helvetica, Arial, sans-serif"
THEMES = {
    "light": dict(bg="#F5F7F6", ink="#1E2A27", soft="#5B6B66", line="#D3DCD8", box="#FFFFFF",
                  msg="#2C6E9B", msgText="#FFFFFF", passc="#23825A", fail="#B42343", sv="#5A4FCF", ghost="#8A9792"),
    "dark": dict(bg="#121A18", ink="#E3ECE8", soft="#9AAAA4", line="#2F3D39", box="#1A2522",
                 msg="#5B9BD0", msgText="#0E1513", passc="#4CC08A", fail="#F0607A", sv="#9C93FF", ghost="#6F7E79"),
}

def hero(t):
    D = 16  # seconds per loop
    def pct(s): return f"{s / D * 100:.2f}%"
    # Envelope keyframes: (start, end) seconds, from x, to x.
    def env(name, t0, t1, x0, x1):
        return f"""
    @keyframes {name} {{
      0%, {pct(t0 - 0.01)} {{ opacity: 0; transform: translateX({x0}px); }}
      {pct(t0)} {{ opacity: 1; transform: translateX({x0}px); }}
      {pct(t1)} {{ opacity: 1; transform: translateX({x1}px); }}
      {pct(t1 + 0.3)}, 100% {{ opacity: 0; transform: translateX({x1}px); }}
    }}"""
    def show(name, t0, t1):
        return f"""
    @keyframes {name} {{
      0%, {pct(t0 - 0.01)} {{ opacity: 0; }}
      {pct(t0 + 0.25)} {{ opacity: 1; }}
      {pct(t1)} {{ opacity: 1; }}
      {pct(t1 + 0.4)}, 100% {{ opacity: 0; }}
    }}"""
    def stamp(name, t0, t1):
        return f"""
    @keyframes {name} {{
      0%, {pct(t0 - 0.01)} {{ opacity: 0; transform: translate(440px, 240px) rotate(-8deg) scale(1.6); }}
      {pct(t0 + 0.35)} {{ opacity: 1; transform: translate(440px, 240px) rotate(-8deg) scale(1); }}
      {pct(t1)} {{ opacity: 1; transform: translate(440px, 240px) rotate(-8deg) scale(1); }}
      {pct(t1 + 0.4)}, 100% {{ opacity: 0; transform: translate(440px, 240px) rotate(-8deg) scale(1); }}
    }}"""
    A, Q, P = 238, 418, 600  # envelope x: leaving order, at queue, arriving at payment
    css = "".join([
        env("e1", 0.4, 2.2, A, P), env("e2", 1.6, 3.2, Q, P),
        env("f1", 8.4, 10.2, A, P), env("f2", 9.6, 11.2, Q, P),
        show("cap1", 0.0, 7.4), show("cap2", 8.0, 15.4),
        show("sv1", 1.3, 3.0), show("sv2", 9.3, 11.0),
        show("r11", 2.2, 7.4), show("r12", 3.2, 7.4), show("r21", 10.2, 15.4), show("r22", 11.2, 15.4),
        stamp("st1", 4.0, 7.4), stamp("st2", 12.0, 15.4),
    ])
    def envelope(anim):
        return f"""<g style="animation: {anim} {D}s linear infinite; opacity: 0">
      <rect x="0" y="86" width="72" height="34" rx="6" fill="{t['msg']}"/>
      <path d="M4 90 L36 108 L68 90" fill="none" stroke="{t['msgText']}" stroke-width="2" opacity=".75"/>
      <text x="36" y="134" text-anchor="middle" font-size="11" fill="{t['soft']}">pay $129</text>
    </g>"""
    def row(anim, y, text, color, strike=False):
        deco = ' text-decoration="line-through"' if strike else ""
        return f"""<g style="animation: {anim} {D}s linear infinite; opacity: 0">
      <line x1="640" y1="{y + 8}" x2="840" y2="{y + 8}" stroke="{t['line']}"/>
      <text x="646" y="{y}" font-size="14" fill="{color}"{deco}>{text}</text>
    </g>"""
    def stampg(anim, word, sub, color):
        return f"""<g style="animation: {anim} {D}s ease-out infinite; opacity: 0">
      <rect x="-112" y="-30" width="224" height="60" rx="8" fill="none" stroke="{color}" stroke-width="3"/>
      <text x="0" y="-2" text-anchor="middle" font-size="24" font-weight="800" letter-spacing="2" fill="{color}">{word}</text>
      <text x="0" y="18" text-anchor="middle" font-size="12" fill="{color}">{sub}</text>
    </g>"""
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 880 300" width="880" height="300" role="img"
  aria-label="Animation: a payment message is delivered twice. Without the fix the customer is charged twice and StateVerdict reports FAIL; with the fix the duplicate is ignored and it reports PASS.">
  <style>
    text {{ font-family: {FONT}; }}
    {css}
    @media (prefers-reduced-motion: reduce) {{ g {{ animation: none !important; }} }}
  </style>
  <rect width="880" height="300" rx="14" fill="{t['bg']}"/>
  <g style="animation: cap1 {D}s linear infinite; opacity: 0">
    <text x="40" y="44" font-size="18" font-weight="700" fill="{t['ink']}">Without the fix: the payment message arrives twice</text>
  </g>
  <g style="animation: cap2 {D}s linear infinite; opacity: 0">
    <text x="40" y="44" font-size="18" font-weight="700" fill="{t['ink']}">With the fix: the same duplicate, one charge</text>
  </g>

  <rect x="40" y="76" width="190" height="64" rx="10" fill="{t['box']}" stroke="{t['line']}"/>
  <text x="135" y="104" text-anchor="middle" font-size="15" font-weight="700" fill="{t['ink']}">Order service</text>
  <text x="135" y="124" text-anchor="middle" font-size="12" fill="{t['soft']}">customer buys shoes</text>

  <line x1="230" y1="108" x2="640" y2="108" stroke="{t['line']}" stroke-width="2" stroke-dasharray="4 6"/>
  <rect x="390" y="54" width="100" height="22" rx="11" fill="{t['bg']}" stroke="{t['line']}"/>
  <text x="440" y="69" text-anchor="middle" font-size="11" fill="{t['soft']}">message queue</text>

  <g style="animation: sv1 {D}s linear infinite; opacity: 0">
    <rect x="352" y="150" width="176" height="28" rx="14" fill="none" stroke="{t['sv']}" stroke-width="1.5"/>
    <text x="440" y="169" text-anchor="middle" font-size="12" font-weight="600" fill="{t['sv']}">StateVerdict: deliver it again</text>
  </g>
  <g style="animation: sv2 {D}s linear infinite; opacity: 0">
    <rect x="352" y="150" width="176" height="28" rx="14" fill="none" stroke="{t['sv']}" stroke-width="1.5"/>
    <text x="440" y="169" text-anchor="middle" font-size="12" font-weight="600" fill="{t['sv']}">StateVerdict: deliver it again</text>
  </g>

  {envelope("e1")}{envelope("e2")}{envelope("f1")}{envelope("f2")}

  <rect x="640" y="76" width="200" height="64" rx="10" fill="{t['box']}" stroke="{t['line']}"/>
  <text x="740" y="104" text-anchor="middle" font-size="15" font-weight="700" fill="{t['ink']}">Payment service</text>
  <text x="740" y="124" text-anchor="middle" font-size="12" fill="{t['soft']}">replies 200 OK every time</text>
  <text x="646" y="164" font-size="12" font-weight="600" fill="{t['soft']}">Charges in the database</text>

  {row("r11", 186, "$129 charged", t['ink'])}{row("r12", 210, "$129 charged again", t['fail'])}
  {row("r21", 186, "$129 charged", t['ink'])}{row("r22", 210, "duplicate ignored", t['ghost'])}

  <text x="40" y="196" font-size="13" fill="{t['soft']}">Every service says OK.</text>
  <text x="40" y="216" font-size="13" fill="{t['soft']}">StateVerdict checks the money instead.</text>

  {stampg("st1", "FAIL", "one order, charged twice", t['fail'])}
  {stampg("st2", "PASS", "one order, one charge", t['passc'])}
</svg>
"""

def results(t):
    rows = [
        ("Planted bugs caught (6)", [("Normal tests", 6, 6, t['msg']), ("StateVerdict", 6, 6, t['sv'])]),
        ("False alarms on harmless changes (3)", [("Normal tests", 1, 3, t['msg']), ("StateVerdict", 0, 3, t['sv'])]),
    ]
    W0, W = 300, 420
    out = []
    y = 64
    for title, bars in rows:
        out.append(f'<text x="40" y="{y}" font-size="15" font-weight="700" fill="{t["ink"]}">{title}</text>')
        y += 16
        for name, v, total, color in bars:
            w = W * v / total
            out.append(f'<text x="40" y="{y + 19}" font-size="13" fill="{t["soft"]}">{name}</text>')
            out.append(f'<rect x="{W0}" y="{y + 4}" width="{W}" height="22" rx="4" fill="{t["line"]}" opacity=".55"/>')
            if v:
                out.append(f'<rect class="bar" x="{W0}" y="{y + 4}" width="{w:.0f}" height="22" rx="4" fill="{color}"/>')
            out.append(f'<text x="{W0 + W + 12}" y="{y + 20}" font-size="14" font-weight="700" fill="{t["ink"]}">{v} of {total}</text>')
            y += 32
        y += 26
    h = y - 4
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 880 {h}" width="880" height="{h}" role="img"
  aria-label="Measured on 6 planted bugs and 3 harmless changes: normal tests caught 6 of 6 bugs with 1 false alarm; StateVerdict caught 6 of 6 with no false alarms.">
  <style>
    text {{ font-family: {FONT}; }}
    .bar {{ transform-box: fill-box; transform-origin: left; animation: grow 1.2s ease-out 1 both; }}
    @keyframes grow {{ from {{ transform: scaleX(0); }} to {{ transform: scaleX(1); }} }}
    @media (prefers-reduced-motion: reduce) {{ .bar {{ animation: none; }} }}
  </style>
  <rect width="880" height="{h}" rx="14" fill="{t['bg']}"/>
  <text x="40" y="34" font-size="12" fill="{t['soft']}">Measured in CI on the same planted bugs. Fewer false alarms is better.</text>
  {''.join(out)}
</svg>
"""

for name, theme in THEMES.items():
    (OUT / f"hero-{name}.svg").write_text(hero(theme))
    (OUT / f"results-{name}.svg").write_text(results(theme))
print("wrote", sorted(p.name for p in OUT.glob("*.svg")))
