from pathlib import Path

from pydantic import SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

RAG_ROOT = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix='MES_KNOWLEDGE_', extra='ignore', hide_input_in_errors=True)
    data_dir: Path = RAG_ROOT / 'data'
    qdrant_url: str = 'http://127.0.0.1:16333'
    qdrant_read_key: SecretStr
    api_key: SecretStr
    threads: int = 4

    @field_validator('data_dir')
    @classmethod
    def absolute_data(cls, path):
        if not path.is_absolute():
            raise ValueError('Data directory must be absolute')
        return path

    @field_validator('threads')
    @classmethod
    def bounded_threads(cls, count):
        if not 1 <= count <= 8:
            raise ValueError('CPU threads must be between 1 and 8')
        return count

    @field_validator('qdrant_read_key', 'api_key')
    @classmethod
    def key_required(cls, value):
        if len(value.get_secret_value()) < 24:
            raise ValueError('A locally configured service credential is required')
        return value
