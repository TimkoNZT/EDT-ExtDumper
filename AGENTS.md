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
| Bundle version                  | `1.0.0`                         |

## Environment

- EDT: `2026.1.1+1` (`C:\Program Files\1C\1CE\components\1c-edt-2026.1.1+1-x86_64\`)
- JDK: 21 (BellSoft Liberica), `--release 17` для компиляции
- Plugins: `{edt}/plugins/`

## Key dependencies (OSGi bundles)

- `com._1c.g5.v8.dt.core` [27.0.0,28.0.0) — `IV8ProjectManager`
- `com._1c.g5.v8.dt.platform.services.core` [23.0.0,24.0.0) — `IExternalObjectDumpSupport`
- `com._1c.g5.v8.dt.metadata` [19.0.0,20.0.0) — `ExternalDataProcessor`, `ExternalReport`
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
- P2 publisher оставляет мусор `content_xml/`, `artifacts_xml/` — чистится в build.ps1
- **Plugin-Security (unsigned)**: при установке через p2 Eclipse показывает предупреждение "Warning: You are installing software that contains unsigned content". Это нормально — JAR не подписан сертификатом (для open-source плагинов стандартная практика). Функциональность не страдает. Достаточно нажать "Install anyway" / "Yes".
