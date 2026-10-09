# MCEF VulkanMod Add-on

Отдельный **клиентский Fabric-аддон для Minecraft 1.21.11**. Он заменяет только последний этап вывода готового браузерного кадра. Это не порт Chromium/JCEF на Vulkan и не замена MCEF.

## Установка

В папке `mods` должны находиться **три отдельных мода**:

1. **MCEF 2.2.0 для Fabric / 1.21.11 из этого репозитория**.
2. **VulkanMod для Minecraft 1.21.11**, версия не ниже 0.5.8.
3. **`mcef-vulkan-addon-1.0.0-1.21.11.jar`**.

Нужны Java 21, Fabric Loader не ниже 0.18.3 и зависимости оригинального MCEF (Fabric API). Скачивание нативных библиотек Chromium по-прежнему выполняет MCEF. Аддон не содержит копии MCEF, JCEF, Chromium, VulkanMod или Vulkan/LWJGL natives.

Аддон не предназначен для NeoForge: VulkanMod использует Fabric. Оригинальные Fabric/NeoForge-сборки MCEF остаются самостоятельными и не требуют аддона.

**Важно:** аддон привязан к реализации MCEF в этой ветке, включая метод `onPaintRenderThread_MCEF`. Другая сборка с тем же номером версии может иметь другую структуру. Обязательные mixins останавливают запуск при несовпадении точек подключения, а не молча возвращаются к OpenGL. Работа со всеми выпусками VulkanMod выше минимальной версии не подтверждена игровым тестированием.

## Сборка

Из корня репозитория, с установленным JDK 21:

```sh
./gradlew cloneJcef
./gradlew :fabric:build :vulkan-addon:build
```

Результаты:

- оригинальный MCEF: `fabric/build/libs/`;
- отдельный аддон: `vulkan-addon/build/libs/mcef-vulkan-addon-1.0.0-1.21.11.jar`.

Устанавливайте remapped jar без суффиксов `-sources` / `-dev`. Исходники и ресурсы `common` **не копируются** в аддон. Зависимости MCEF/JCEF нужны только для компиляции; `:fabric` подключается для среды разработки.

Аддон использует Minecraft `GpuDevice` / `CommandEncoder`, поэтому jar VulkanMod не нужен для компиляции. Для запуска dev-клиента укажите настоящий jar VulkanMod **для 1.21.11**:

```sh
./gradlew :vulkan-addon:runClient -PvulkanmodJar=/absolute/path/to/VulkanMod.jar
```

Для Windows используйте `gradlew.bat` и подходящий путь к jar. Параметр `vulkanmodJar` не включает VulkanMod в распространяемый артефакт. Без него или без VulkanMod в `vulkan-addon/run_client/mods` dev-клиент не пройдёт проверку обязательных зависимостей.

## Сборка через GitHub Actions

Workflow [`.github/workflows/build-vulkan-addon.yml`](../.github/workflows/build-vulkan-addon.yml) запускается при изменении кода/конфигурации в push или pull request. Он:

1. Клонирует исходники вместе с закреплённым JCEF submodule.
2. Устанавливает Temurin JDK 21 и использует Gradle Wrapper репозитория.
3. Собирает оригинальный Fabric MCEF и отдельный аддон, выполняя CPU-тесты.
4. Проверяет содержимое remapped jar, mixin refmap, отсутствие встроенных зависимостей в аддоне и корректный JCEF commit в manifest оригинального MCEF.
5. Публикует артефакт `mcef-vulkan-fabric-1.21.11-<номер запуска>` с двумя installable jar и `SHA256SUMS`. Отдельный артефакт `mcef-vulkan-tests-<номер запуска>` содержит JUnit XML и HTML-отчёт.

Артефакты успешного запуска доступны в разделе **Actions → Build MCEF Vulkan add-on → Artifacts** и хранятся 14 дней. Workflow не скачивает Chromium natives и не запускает игру; проверка Vulkan на настоящем GPU остаётся отдельным шагом.

## Как работает мост

```text
Chromium / JCEF — без изменений
    │ onPaint: готовый BGRA bitmap + dirty rects
    ▼
MCEFBrowser.onPaint — без изменений
    │ копирование native buffer и передача на render thread
    ▼
аддон: stride-aware обновление кеша готового кадра / popup
    │ premultiplied BGRA → straight RGBA, packed dirty rectangle
    ▼
RenderSystem.getDevice().createCommandEncoder().writeToTexture(...)
    │ активный GPU-бэкенд VulkanMod
    ▼
VulkanMod: staging buffer → Vulkan image → синхронизация перед отрисовкой
    ▼
существующий MCEFDirectTexture / TextureManager / GuiGraphics
```

### Почему недостаточно существующего fallback MCEF

`VkGpuTexture` в VulkanMod наследуется от `GlTexture`. Поэтому проверки `texture instanceof GlTexture` в MCEF выбирают OpenGL-ветку, хотя активное устройство — Vulkan. Аддон проверяет **бэкенд устройства**, а не тип текстуры, и отменяет обе GL-загрузки MCEF. В исходниках аддона нет вызовов OpenGL.

Используется GPU API, уже реализованный VulkanMod; аддон не управляет Vulkan instance/device, swapchain, render pass, fence или GPU-барьерами самостоятельно. Проверен контракт исходников VulkanMod для 1.21.11: [commit `087ef24`](https://github.com/xCollateral/VulkanMod/tree/087ef24e58e5522dec5ccb44f279768786bfb6da), классы `VkGpuDevice`, `VkCommandEncoder`, `VkGpuTexture`, `VulkanImage`.

### Dirty rects, popup и память

- CEF передаёт **полный bitmap**, даже когда изменился маленький прямоугольник. Учитываются полный row stride и координаты источника; в GPU передаётся плотно упакованный RGBA-прямоугольник.
- Первый кадр, изменение размеров и пересоздание GPU-текстуры загружаются целиком; последующие кадры обновляют только затронутые области.
- Кеш основной страницы не затирается popup-кадрами. При скрытии/перемещении popup восстанавливается актуальная область страницы, в том числе без нового `onPaint` от Chromium.
- Отрицательные координаты и выход popup за границы окна обрезаются с корректным смещением источника. Невалидные буферы и переполнение размеров отвергаются.
- CEF alpha остаётся premultiplied в CPU-кеше. Popup компонуется в этом формате, затем кадр переводится в straight alpha для стандартного текстурированного GUI-пайплайна Minecraft.
- У каждого браузера собственный кеш. Один native upload buffer переиспользуется и явно освобождается при `cleanup`. View закрывается **до** старой GPU-текстуры при resize/закрытии.
- Отложенный paint после закрытия не создаёт новую текстуру.

### Совместимость для модов, использующих MCEF

Сохраняются `getTexture()`, `getTextureIdentifier()`, `isTextureReady()` и обычный вывод через `GuiGraphics.blit(RenderPipelines.GUI_TEXTURED, ...)`. URL, ввод, курсоры, звук, сеть, кеш и жизненный цикл Chromium не заменяются.

**Старый вывод через сырой OpenGL texture ID не поддерживается:** на Vulkan `getTextureID()` возвращает `0`. `supportsDirtyRectUpload()` возвращает `false`, потому что этот старый контракт предполагает `GL_UNPACK_*`; внутренний Vulkan-путь аддона при этом поддерживает dirty rects. Моду, который рисует браузер собственными OpenGL-командами, нужно использовать GPU-текстуру / Identifier и совместимый пайплайн.

Низкоуровневый `MCEFRenderer.onPaint(buffer, x, y, width, height)` на Vulkan принимает **packed BGRA sub-image**, а не полный bitmap со скрытым GL pixel-store состоянием. Стандартный `MCEFBrowser` использует новый stride-aware мост, не этот устаревший контракт.

## Проверки

CPU-компоновка покрыта JUnit-тестами без запуска Chromium/GPU:

```sh
./gradlew :vulkan-addon:test
```

Проверяются цвета и ориентация, stride, dirty rects, resize, popup до первого кадра, скрытие/перемещение/обрезка popup, premultiplied alpha, позиции/лимиты буферов, переполнение размеров, независимость браузеров и очистка кеша.

### Ручная проверка с настоящим VulkanMod

1. Запустить dev-клиент с указанным jar VulkanMod, дождаться инициализации MCEF.
2. Войти в мир, нажать F12 (пример браузера доступен в dev-среде).
3. Открыть `file:///absolute/path/to/vulkan-addon/src/test/resources/browser-render-smoke-test.html` через адресную строку. На Windows: `file:///C:/path/to/...`.
4. Проверить красный/зелёный/синий, правильную ориентацию текста, анимацию и ввод.
5. Открыть/закрыть `<select>` в центре и возле края окна. Не должно быть следов popup, пропавших областей или ошибок размеров.
6. Изменить размер окна и GUI scale, открыть/закрыть браузер несколько раз; отдельно проверить прозрачный браузер и несколько одновременно существующих экземпляров через MCEF API.
7. В логе должен появиться `MCEF Vulkan bridge active: completed Chromium frames -> VulkanMod GPU textures`. Проверить отсутствие ошибок `No OpenGL context`, закрытых texture views и GPU validation errors.

**Статус проверки:** синтаксис Java, ресурсы и точки Mixin проверены статически. Для компиляции, JUnit и проверки jar добавлен GitHub Actions workflow; результат каждого запуска и бинарные артефакты доступны в Actions. Игровая совместимость требует ручного прогона: CI не запускает Minecraft/Chromium/Vulkan GPU.
