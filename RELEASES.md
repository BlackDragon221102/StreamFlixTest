# Release Workflow

## Pubblicare una nuova release

```powershell
.\scripts\publish-release.ps1 -Version v1.7.104
```

Passaggi:
- aggiorna `versionName`
- incrementa `versionCode`
- crea commit
- pusha `main`
- crea e pusha il tag
- GitHub Actions crea la release e allega l'APK

Secrets GitHub richiesti per una release installabile:
- `TMDB_API_KEY`
- `RELEASE_KEYSTORE_BASE64`
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

## Eliminare un tag release

```powershell
.\scripts\delete-release-tag.ps1 -Version v1.7.104
```

Passaggi:
- elimina il tag locale
- elimina il tag remoto

Nota:
- se la pagina della release esiste ancora su GitHub, eliminala anche dal sito GitHub
