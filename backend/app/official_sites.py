"""Verified official websites. The scam shield only ever offers URLs from this list; a URL from
a suspicious message is never opened. Extend per region.
"""

import re
import unicodedata
from dataclasses import dataclass


@dataclass(frozen=True)
class Official:
    name: str
    url: str
    domains: tuple[str, ...]
    aliases: tuple[str, ...]


OFFICIAL: list[Official] = [
    Official("State Bank of India", "https://www.onlinesbi.sbi", ("sbi.co.in", "onlinesbi.sbi", "sbi"), ("sbi", "state bank")),
    Official("HDFC Bank", "https://www.hdfcbank.com", ("hdfcbank.com",), ("hdfc",)),
    Official("ICICI Bank", "https://www.icicibank.com", ("icicibank.com",), ("icici",)),
    Official("Axis Bank", "https://www.axisbank.com", ("axisbank.com",), ("axis bank",)),
    Official("Canara Bank", "https://canarabank.com", ("canarabank.com", "canarabank.in"), ("canara",)),
    Official("Punjab National Bank", "https://www.pnbindia.in", ("pnbindia.in", "pnb.bank.in"), ("pnb", "punjab national")),
    Official("Bank of Baroda", "https://www.bankofbaroda.in", ("bankofbaroda.in", "bankofbaroda.com"), ("bank of baroda", "bob")),
    Official("India Post", "https://www.indiapost.gov.in", ("indiapost.gov.in",), ("india post", "indiapost", "speed post")),
    Official("Income Tax Department", "https://www.incometax.gov.in", ("incometax.gov.in",), ("income tax", "itr", "refund")),
    Official("UIDAI (Aadhaar)", "https://uidai.gov.in", ("uidai.gov.in",), ("aadhaar", "aadhar", "uidai")),
    Official("EPFO", "https://www.epfindia.gov.in", ("epfindia.gov.in",), ("epfo", "pf", "provident fund")),
    Official("Electricity (BESCOM)", "https://bescom.karnataka.gov.in", ("bescom.karnataka.gov.in", "bescom.co.in"), ("bescom", "electricity bill", "power cut")),
    Official("National Cyber Crime Portal", "https://cybercrime.gov.in", ("cybercrime.gov.in",), ("cyber crime", "police")),
    Official("Blinkit", "https://blinkit.com", ("blinkit.com",), ("blinkit",)),
    Official("WhatsApp", "https://www.whatsapp.com", ("whatsapp.com", "wa.me"), ("whatsapp",)),
]

# Where to report fraud in India; always safe to offer.
CYBER_CRIME = OFFICIAL[[o.name for o in OFFICIAL].index("National Cyber Crime Portal")]


def is_official_domain(host: str) -> bool:
    host = host.lower().rstrip(".")
    return any(host == d or host.endswith("." + d) for o in OFFICIAL for d in o.domains)


def find_by_alias(text: str) -> Official | None:
    t = text.lower()
    for o in OFFICIAL:
        if any(re.search(rf"\b{re.escape(a)}\b", t) for a in o.aliases):
            return o
    return None


def find_by_name(name: str | None) -> Official | None:
    return find_by_alias(name) if name else None


def _skeleton(s: str) -> str:
    """Normalise confusables so 'sbi-kyc-update.in' / 'hdfcbank-secure.co' / 'ıcıcı' compare."""
    s = unicodedata.normalize("NFKD", s).encode("ascii", "ignore").decode().lower()
    return s.translate(str.maketrans({"0": "o", "1": "l", "3": "e", "5": "s", "@": "a"})).replace("-", "")


def is_lookalike(host: str) -> Official | None:
    """A domain that mentions a known brand but is not one of its official domains."""
    if is_official_domain(host):
        return None
    sk = _skeleton(host)
    for o in OFFICIAL:
        for d in o.domains:
            brand = _skeleton(d.split(".")[0])
            if len(brand) >= 3 and brand in sk:
                return o
    return None
