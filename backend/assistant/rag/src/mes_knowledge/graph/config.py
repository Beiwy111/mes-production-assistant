from pathlib import Path
from dotenv import dotenv_values
from pydantic import BaseModel, ConfigDict, SecretStr, model_validator

from ..config import RAG_ROOT


class GraphSettings(BaseModel):
    model_config = ConfigDict(extra='forbid', hide_input_in_errors=True)
    enabled: bool = False
    uri: str = 'bolt://127.0.0.1:17687'
    user: str = 'neo4j'
    password: SecretStr | None = None

    @model_validator(mode='after')
    def validate_credentials(self):
        if self.enabled and (self.password is None or len(self.password.get_secret_value()) < 32):
            raise ValueError('Private graph credential required')
        return self


def graph_settings(path: Path | None = None):
    path = path or RAG_ROOT.parent / 'deploy/.env.neo4j.local'
    values = dotenv_values(path, interpolate=False) if path.exists() else {}
    return GraphSettings(**{k.removeprefix('MES_GRAPH_').lower(): v for k,v in values.items() if k.startswith('MES_GRAPH_')})


def open_graph(settings):
    from .store import GraphStore
    if settings.password is None or len(settings.password.get_secret_value()) < 32:
        raise ValueError('Configure private graph credentials locally')
    return GraphStore(settings.uri, settings.user, settings.password.get_secret_value())
