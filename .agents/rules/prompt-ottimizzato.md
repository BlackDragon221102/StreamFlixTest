---
trigger: always_on
---

**Stato dell'Arte**
`StreamFlix` è un’app Android multi-modulo (`app`, `navigation`, `retrofit-jsoup-converter`) con architettura MVVM, Room per persistenza, scraping via `Jsoup`/`Retrofit`/`OkHttp`, player ExoPlayer/Media3 e doppio binario UI `mobile`/`tv`.

Entry point e shell:
- [StreamFlixApp.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\StreamFlixApp.kt)
- [MainMobileActivity.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\activities\main\MainMobileActivity.kt)
- [MainTvActivity.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\activities\main\MainTvActivity.kt)

Persistenza:
- [AppDatabase.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\database\AppDatabase.kt)
- DAO chiave: [MovieDao.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\database\dao\MovieDao.kt), [TvShowDao.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\database\dao\TvShowDao.kt), [EpisodeDao.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\database\dao\EpisodeDao.kt), [SeasonDao.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\database\dao\SeasonDao.kt)

Provider/enrichment:
- Provider critico: [StreamingCommunityProvider.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\providers\StreamingCommunityProvider.kt)
- Enrichment TMDB: [TmdbUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TmdbUtils.kt), [TMDb3.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TMDb3.kt)

Cache/cataloghi:
- [UiCacheStore.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\UiCacheStore.kt)
- [CatalogRefreshUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\CatalogRefreshUtils.kt)
- [CatalogRefreshWorker.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\CatalogRefreshWorker.kt)
- [CatalogRefreshScheduler.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\CatalogRefreshScheduler.kt)
- [CatalogSeedUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\CatalogSeedUtils.kt)
- [PrefetchUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\PrefetchUtils.kt)

Player:
- [PlayerMobileFragment.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\fragments\player\PlayerMobileFragment.kt)
- [PlayerTvFragment.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\fragments\player\PlayerTvFragment.kt)
- [PlayerGestureHelper.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\PlayerGestureHelper.kt)

**Log dello Storico (Modifiche Apportate)**
1. Stabilizzato `StreamingCommunityProvider`:
- rimossi `runBlocking` e lavoro bloccante da getter/setter
- `ensureVersion()` sospesa
- cache artwork TMDB con miss/null
- rebuild service più sicuro

2. Hardened resolver/player:
- [WebViewResolver.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\WebViewResolver.kt): timeout/cancel ora falliscono esplicitamente
- [PlayerGestureHelper.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\PlayerGestureHelper.kt): niente scope orfano, `release()`
- fragments player: un solo listener, un solo handler progress, cleanup corretto

3. Pulizia adapter/carousel:
- [CategoryViewHolder.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\adapters\viewholders\CategoryViewHolder.kt): `release()`
- [AppAdapter.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\adapters\AppAdapter.kt): stati nested per chiave stabile, load-more robusto, recycle cleanup

4. Cataloghi:
- refresh in background via WorkManager
- gating startup in [MainMobileActivity.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\activities\main\MainMobileActivity.kt) per evitare ingresso con dati vecchi che si aggiornano dopo
- seed DB e prefetch dettagli migliorati

5. Performance:
- [HeroColorUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\HeroColorUtils.kt): downscale + `getPixels()`
- [GlideCustomModule.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\ui\GlideCustomModule.kt): cache path corretto

6. Genre sorting:
- [GenreViewModel.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\fragments\genre\GenreViewModel.kt): `SortMode` nel ViewModel
- sort server-side per StreamingCommunity
- race fix tra `_sourceState` e `_sortMode`
- popup custom/UX mobile rifinita in [GenreMobileFragment.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\fragments\genre\GenreMobileFragment.kt)

7. Header touch pass-through fix:
- Home/Movies/TvShows/Genre mobile: header ora consuma i tocchi e non apre contenuti sottostanti

8. TMDB artwork pipeline:
- scoring match titolo/anno in [TmdbUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TmdbUtils.kt)
- picker immagini score-based in [TMDb3.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TMDb3.kt)
- utility nuova [ArtworkPreferenceUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\ArtworkPreferenceUtils.kt)
- `Movie.merge()` e `TvShow.merge()` ora aggiornano anche metadata/poster/banner
- merge ViewModel e seed DB ora preferiscono TMDB su provider
- cache cataloghi invalidate tramite version bump in `CatalogRefreshUtils`

9. Release pipeline:
- [scripts\publish-release.ps1](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\scripts\publish-release.ps1)
- [scripts\delete-release-tag.ps1](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\scripts\delete-release-tag.ps1)
- [release.yml](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\.github\workflows\release.yml)
- note release manuali in `release-notes\`

**Debito Tecnico e Bug Noti**
1. Pipeline TMDB ancora da validare su casi reali:
- possibili residui: `tmdb_id` errato, anno errato, titolo provider troppo sporco, cache device pregresse
- area critica: [StreamingCommunityProvider.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\providers\StreamingCommunityProvider.kt), [TmdbUtils.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TmdbUtils.kt), [TMDb3.kt](C:\Users\Antonino\Desktop\StreamflixNewInterface - Copia\streamflix\app\src\main\java\com\streamflixreborn\streamflix\utils\TMDb3.kt)

2. Warning compile non bloccanti rimasti:
- deprecated `TRIM_MEMORY_RUNNING_LOW`, `statusBarColor`, `overridePendingTransition`, `databaseEnabled`
- parameter naming warning su `CookieJar` in alcuni provider

3. Provider scraping intrinsecamente fragili:
- ogni refactor deve assumere rotture future lato HTML/API esterne

4. Nessuna copertura test significativa su:
- sorting `Genre`
- merge cataloghi/DB
- pipeline immagini TMDB
- player lifecycle

**Visione Strategica**
1. Priorità assoluta a stabilità percepita:
- no feature vistose che peggiorano scraping, player, cataloghi, immagini

2. Refresh invisibile:
- aprire l’app con dati già aggiornati
- non mostrare contenuti che si riscrivono davanti all’utente

3. Artwork coerente:
- fonte primaria TMDB italiano
- fallback TMDB inglese
- provider image solo come fallback finale

4. Mobile/TV:
- non unificare l’UX forzatamente
- unificare semantica dati, caching, provider, fix lifecycle

5. Release discipline:
- changelog manuale per versione
- build verificata
- niente auto release notes vaghe

**Istruzioni Operative**
1. Non reintrodurre rete o `runBlocking` in getter/setter provider.

2. Se tocchi player:
- un solo listener
- un solo handler progress
- cleanup in `onDestroyView()`
- niente scope orfane

3. Se tocchi `Movie.merge()` o `TvShow.merge()`, fai audit completo:
- impattano DAO `save()`, dettagli, favorites/history, poster/banner

4. Se tocchi `Genre`, non riportare il sort nel Fragment:
- il race storico è già stato risolto nel ViewModel

5. Per TMDB immagini, rispettare sempre:
- `italiano -> inglese -> provider`
- se cambi la strategia immagini, invalidare cache cataloghi

6. Prima di correggere un bug immagini/cataloghi, verificare sempre 4 layer:
- provider live
- cache disco
- DB Room
- dettaglio fetched/prefetch

7. Processo release:
```powershell
.\scripts\publish-release.ps1 -Version vX.Y.Z
```

8. Compile di riferimento:
```powershell
.\gradlew.bat :app:compileDebugKotlin
```

**Focus immediato consigliato**
1. validare i fix TMDB su casi reali rimasti
2. log mirati su titoli ancora sbagliati
3. solo dopo tornare a nuove feature/UI