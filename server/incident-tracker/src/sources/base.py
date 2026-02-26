from __future__ import annotations

from abc import ABC, abstractmethod

from src.models import RawIncident


class Source(ABC):
    """Base class for all incident data sources."""

    @property
    @abstractmethod
    def name(self) -> str:
        """Human-readable source name for logging."""
        ...

    @abstractmethod
    async def fetch(self) -> list[RawIncident]:
        """Fetch new incidents from this source.

        Returns a list of RawIncident objects. Structured sources should set
        structured=True and pre-populate lat/lon/category/severity.
        """
        ...
