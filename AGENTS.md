# EDT ExtDumper Plugin

## Правила

1. Каждое решение/находку — документировать здесь.
2. Не полагаться на субагентов для поиска — искать самому.
3. Перед изменением кода — проверить AGENTS.md.

## Goal

Eclipse PDE plugin → контекстное меню "Сгенерировать .epf/.erf" для ExternalDataProcessor / ExternalReport. Работает независимо от чекбокса авто-выгрузки в настройках проекта.

## Binary names

| Элемент                         | Значение                        |
| ------------------------------- | ------------------------------- |
| Bundle-SymbolicName             | `com.nzt.edt.extdumper`         |
| Plugin name (About)             | **EDT ExtDumper**               |
| Feature name (Install)          | **EDT ExtDumper**               |
| P2 category name                | **NZT Tools**                   |
| Command ID                      | `com.nzt.edt.extdumper.generate`|
| Menu item                       | **Генерировать .epf/.erf**      |
| Java package                    | `com.nzt.edt.extdumper`         |
| Bundle version                  | `1.0.1`                         |

## CI: частые проблемы

### Workflow падает на main с exit code 1

**Причина:** `git describe --tags` в шаге "Create ZIP" падает, потому что `actions/checkout@v4` по умолчанию делает shallow clone (depth=1) без тегов.

**Фикс:** добавить `fetch-depth: 0` и `fetch-tags: true` к checkout'у, и обернуть `git describe` в `if ($LASTEXITCODE -ne 0) { $tag = $null }`.

```yaml
- uses: actions/checkout@v4
  with:
    fetch-depth: 0
    fetch-tags: true
```

## Versioning: как поменять версию

**Единственное место правки — `META-INF/MANIFEST.MF`, поле `Bundle-Version`.**

```bash
# После правки:
git add META-INF/MANIFEST.MF build.ps1 dist/p2repo/
.\build.ps1         # пересобрать p2repo и ZIP
git commit -m "Версия X.Y.Z: Bundle-Version, p2repo"
git tag vX.Y.Z
git push origin main && git push origin vX.Y.Z
```

**Важно: GitHub Actions НЕ компилирует и НЕ запускает `build.ps1`.**  
В CI нет ни EDT, ни JDK. Сборка (исходники → JAR → p2repo) делается **локально** через `.\build.ps1`. На GitHub коммитится уже готовый `dist/p2repo/`, CI только упаковывает его в ZIP.

**Что откуда берётся:**

| Артефакт | Откуда версия | Компиляция |
|---|---|---|
| `Bundle-Version` в MANIFEST.MF | **ручная правка** — единственное место | локально |
| `com.nzt.edt.extdumper_X.Y.Z.jar` | `build.ps1` парсит MANIFEST.MF | локально |
| `com.nzt.edt.extdumper.feature_X.Y.Z.jar` | то же | локально |
| `version='X.Y.Z'` в content.xml | publisher читает из JAR-ов | локально |
| `category` + `requires range` в content.xml | `build.ps1` подставляет `$PluginVersion` | локально |
| `edt_extdumper_X.Y.Z.zip` | CI читает из git-тега `vX.Y.Z` | **GitHub** (упаковка готового p2repo) |

**Проверка после сборки:** при установке в Help → Install New Software → Add (Pages URL или Archive → ZIP) должна показываться версия `X.Y.Z`.

## Environment

- EDT: `2025.2.6+4` (`C:\Program Files\1C\1CE\components\1c-edt-2025.2.6+4-x86_64\`)
- JDK: 21 (BellSoft Liberica), `--release 17` для компиляции
- Plugins: `{edt}/plugins/`

## Key dependencies (OSGi bundles)

- `com._1c.g5.v8.dt.core` 26.0.0 — `IV8ProjectManager`
- `com._1c.g5.v8.dt.platform.services.core` 21.0.0 — `IExternalObjectDumpSupport`
- `com._1c.g5.v8.dt.metadata` 18.0.0 — `ExternalDataProcessor`, `ExternalReport`
- `org.eclipse.jface.notifications` [0.7.0,1.0.0) — `NotificationPopup`

## Handler architecture (3 phases)

1. **extractObject(event)** → EObject (MdObject)
2. **resolveProject(object)** → IProject via `IV8ProjectManager.getProject(EObject)` (не URI parsing!)
3. **checkDumpPreconditions + scheduleDump**:
   - force-enable (сохранить `isEnabled` → `setEnabled(true)` → generate → restore в finally)
   - `getDump(wait=true)` — синхронный, но ошибки DumpJob проглатываются
   - `Files.exists()` после getDump — единственный надёжный guard (DumpJob возвращает Path даже при ошибке)
   - Успех → `DumpNotification` (PopupDialog), ошибка → `MessageDialog`

## Key fixes (lessons learned)

| Проблема                                     | Решение                                                        |
| -------------------------------------------- | -------------------------------------------------------------- |
| URI из навигатора — virtual (`virtual:/...`) | `IV8ProjectManager.getProject(EObject)`, не `segment(1)`       |
| `getDump()`, `updateDump()` проверяют `isEnabled` | force-enable обязателен                                   |
| `getDump()` возвращает Path даже при ошибке   | `Files.exists()` после вызова                                  |
| PLUGIN_ID не совпадал с Bundle-SymbolicName  | исправлено на `com.nzt.edt.extdumper`                          |
| Text(SWT.WRAP) на Win игнорирует `setBackground` | Использовать Label(SWT.WRAP)                               |
| `plugin.properties` с кириллицей в UTF-8     | Кодировать в `\uXXXX` (Java .properties = ISO-8859-1)         |
| feature JAR дублировался (`${id}.jar`)       | Исправить на `${id}_${version}.jar`                            |
| `2>&1` с `$ErrorActionPreference=Stop` крашит скрипт | Убрать `2>&1` на native командах                        |

## Build System (`build.ps1`)

- `javac --release 17 -cp "$pluginsDir\*"` — wildcard classpath из папки EDT plugins
- **JDK**: только `$env:JAVA_HOME` (не hardcoded)
- **P2 publisher**: `1cedtc -application org.eclipse.equinox.p2.publisher.FeaturesAndBundlesPublisher`
- **Category injection**: после publisher'а вручную добавляет category IU в `content.xml`
- **ZIP**: архив `dist/p2repo/` с `/` вместо `\` (Java `jar:` protocol требует forward slashes)
- **Output**: `target/*.jar`, `dist/p2repo/`, `dist/edt_extdumper_*.zip`

## GitHub Workflow (`.github/workflows/publish.yml`)

- **main push** → `peaceiris/actions-gh-pages` деплоит `dist/p2repo/` на GitHub Pages
- **v* tag push** → то же + ZIP крепится к GitHub Release
- Не требует EDT в CI — `dist/p2repo/` закоммичен в git (собирается локально)

## Installation

1. **GitHub Pages URL**: `https://timkonzt.github.io/EDT-ExtDumper/`
2. **ZIP из Releases**: Download → Help → Install New Software → Add → Archive
3. **Локально**: `dist/p2repo/` — Add → Local

## Naming conventions

- Plugin name (About): **EDT ExtDumper** — `plugin.properties` → `pluginName`
- Menu: **Генерировать .epf/.erf** — `plugin.properties` → `generateDumpCommandName` (escaped `\uXXXX`)
- Feature label: **EDT ExtDumper** — `build.ps1` inline feature.xml
- P2 category: **NZT Tools** — `build.ps1` category injection (`p2.name`)

## Composite P2 Repository

Для объединения нескольких плагинов: отдельный репозиторий с `compositeContent.xml` + `compositeArtifacts.xml`, которые `<child location='...'>` ссылаются на P2 каждого плагина на GitHub Pages. Eclipse нативно поддерживает композиты.

## Known issues

- `Require-Bundle` в MANIFEST.MF не должен иметь ведущего пробела — парсер воспримет как продолжение предыдущего заголовка
- Версионные диапазоны слишком узкие → `[X.Y.Z,10.0.0)`
- **Три ключевых EDT-бандла** (проверять при смене версии EDT):
  `com._1c.g5.v8.dt.platform.services.core`, `com._1c.g5.v8.dt.metadata`, `com._1c.g5.v8.dt.core`
  Минимальные версии для EDT 2025.2.6: 21.0.0 / 18.0.0 / 26.0.0.
  Для максимальной совместимости используй `bundle-version="X.Y.Z"` (без скобок = `[X.Y.Z,∞)`), а не `[X.Y.Z,W)]`
- P2 publisher оставляет мусор `content_xml/`, `artifacts_xml/` — чистится в build.ps1
- **Plugin-Security (unsigned)**: при установке через p2 Eclipse показывает предупреждение "Warning: You are installing software that contains unsigned content". Это нормально — JAR не подписан сертификатом (для open-source плагинов стандартная практика). Функциональность не страдает. Достаточно нажать "Install anyway" / "Yes".
