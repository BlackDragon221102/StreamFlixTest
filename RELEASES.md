# Release Workflow

## Pubblicare una nuova release

1. crea il changelog reale in `release-notes/vX.Y.Z.md`
2. poi esegui:

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
- la release usa il contenuto di `release-notes/vX.Y.Z.md` come note ufficiali

Esempio changelog:

```md
## Novita
- Migliorata la nav bar inferiore con feedback al tocco piu fluido.
- Aggiornato il link GitHub nelle impostazioni TV.

## Correzioni
- Sistemato il flusso di pubblicazione per evitare tag creati se il push di `main` fallisce.
```

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
