# AI adapter sources

`src/main/java` contains model invocation, provider key rotation, balances, media processing and note assistance. Maven adds this source directory to the backend build; there is no second service or duplicated adapter.

- `MODEL_MODE=demo`: deterministic synthetic fixtures, **not real inference or research accuracy**.
- `MODEL_MODE=live`: cloud providers; configure your own keys and valid model IDs in `.env` or the protected settings UI.
- `MODEL_MODE=http`: custom model endpoint at `MODEL_BASE_URL`.

Provider credentials remain server-side. Never commit keys or real user data.
