"""Prints the text inside the focused node of a uiautomator dump (Compose keeps the text in child nodes),
then its bounds. Usage: focused.py <ui.xml>."""
import sys, xml.etree.ElementTree as ET
try:
    root = ET.parse(sys.argv[1]).getroot()
except Exception:
    sys.exit(0)
for n in root.iter("node"):
    if n.get("focused") == "true":
        texts = [t for t in (m.get("text") for m in n.iter("node")) if t]
        print(" | ".join(texts) + "  @" + n.get("bounds", ""))
        break
