from pydantic import field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    PROJECT_NAME: str = "PoDoc API"
    VERSION: str = "1.6.22"
    DOMAIN: str = "localhost"

    # Database
    DATABASE_URL: str = "postgresql+asyncpg://podoc:podoc_secure_db_pass_123@postgres:5432/podoc"

    @field_validator("DATABASE_URL", mode="before")
    @classmethod
    def assemble_db_connection(cls, v: str) -> str:
        if isinstance(v, str):
            # Render / Railway / Supabase thường trả về postgres:// hoặc postgresql://
            if v.startswith("postgres://"):
                v = v.replace("postgres://", "postgresql+asyncpg://", 1)
            elif v.startswith("postgresql://") and not v.startswith("postgresql+asyncpg://"):
                v = v.replace("postgresql://", "postgresql+asyncpg://", 1)
        return v

    # Admin Account
    ADMIN_USERNAME: str = "admin"
    ADMIN_PASSWORD: str = "podoc_admin_password_change_me"

    # Security & JWT
    JWT_SECRET: str = "super_secure_random_jwt_secret_key_at_least_32_chars_long"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_DAYS: int = 30
    REQUIRE_AUTH: bool = False

    # Rate Limiting
    RATE_LIMIT_LOGIN: str = "5/minute"

    # File Storage (Phase 2)
    FILES_STORAGE_DIR: str = "/data/files"
    MAX_UPLOAD_SIZE_MB: int = 10240
    MAX_DOWNLOAD_SIZE_MB: int = 10240

    # Google Gemini AI / LLM OCR Service
    GEMINI_API_KEY: str = ""
    GEMINI_MODEL: str = "gemini-3.8-flash"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore"
    )


settings = Settings()
