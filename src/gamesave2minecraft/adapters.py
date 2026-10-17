"""Source frontends are agent-authored modules behind one narrow contract."""
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

@dataclass(frozen=True)
class SourceInputs:
    save: Path
    install: Path | None = None
    version: str | None = None

class SourceAdapter(Protocol):
    def probe(self, inputs: SourceInputs) -> dict:
        """Return source/tool identity and supported format evidence without mutation."""
    def export_scene(self, inputs: SourceInputs, output: Path) -> dict:
        """Write source-local resources and return a normalized, coverage-accounted scene."""
    def verify_source(self, inputs: SourceInputs, scene: dict) -> dict:
        """Compare recovered object/state data with a source oracle when available."""
