# Core camera architecture: внедрение `CameraXView` в любой Android UI

Документ описывает архитектурный подход core-модуля камеры и то, как **встраивать** `CameraXView` в **Fragment (XML)**, **Activity (XML)** и **Jetpack Compose** (через `AndroidView`).

---

## Цель core-модуля

`core` предоставляет готовый UI-компонент камеры — `CameraXView`, который:

- сам поднимает CameraX (Preview + ImageAnalysis),
- сам биндится к `LifecycleOwner` из дерева View,
- отдаёт кадры **только** через Flow (`frameFlow`),
- поддерживает два режима управления:
  - **imperative** (прямые вызовы `startCamera()/stopCamera()`),
  - **reactive** (через `bindCommands(StateFlow<CameraCommand>)`).

> Разрешения (camera permission) — **вне** ответственности core: экран, который встраивает `CameraXView`, должен гарантировать, что permission уже выдан.

---

## Текущий контракт интеграции

Файл: `core/src/main/java/com/alezzgo/lunalab/core/camera/CameraXView.kt`

- **View-компонент**: `CameraXView : FrameLayout`
- **Поток кадров**: `val frameFlow: SharedFlow<FrameData>`
- **Управление**:
  - `fun setLensFacing(facing: Int)` (используйте `CameraSelector.LENS_FACING_*`)
  - `fun setAutoStart(enabled: Boolean)`
  - `fun bindCommands(commands: StateFlow<CameraCommand>)`
  - `fun startCamera(forceRebind: Boolean = false)`
  - `fun stopCamera()`

Команды: `core/src/main/java/com/alezzgo/lunalab/core/camera/CameraContract.kt`

- `CameraCommand.Start`
- `CameraCommand.Stop`

> `FrameData` считать **внутренним контрактом, который будет меняться**: в этой документации мы **не фиксируем** его формат и не описываем поля.

---

## Инварианты (что важно для всех UI)

- `CameraXView` должен жить в дереве View, где есть `LifecycleOwner`.
  - внутри используется `findViewTreeLifecycleOwner()`.
- `frameFlow` собираем в lifecycle-aware скоупе (`repeatOnLifecycle`), чтобы:
  - не держать обработку в фоне, когда экран невидим,
  - не протекать подписками.
- Управление стартом/стопом выбирается на уровне экрана:
  - **Auto**: `setAutoStart(true)` (по умолчанию) → старт при attach, stop при detach.
  - **Manual/Reactive**: `setAutoStart(false)` → вы управляете сами (`commands` или `start/stop`).

---

## Встраивание: Fragment XML

### Layout

```xml
<!-- res/layout/fragment_camera.xml -->
<com.alezzgo.lunalab.core.camera.CameraXView
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### Fragment (reactive управление + сбор кадров)

```kotlin
class CameraFragment : Fragment(R.layout.fragment_camera) {

    private val vm: CameraVm by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val cameraView = view.findViewById<CameraXView>(R.id.cameraView)

        // Управление: reactive
        cameraView.setAutoStart(false)
        cameraView.bindCommands(vm.cameraCommands)

        // Пример выбора камеры
        cameraView.setLensFacing(CameraSelector.LENS_FACING_FRONT)

        // Кадры: только Flow
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraView.frameFlow.collect { frame ->
                    vm.onFrame(frame)
                }
            }
        }
    }
}

class CameraVm : ViewModel() {
    private val _cameraCommands = MutableStateFlow<CameraCommand>(CameraCommand.Stop)
    val cameraCommands: StateFlow<CameraCommand> = _cameraCommands

    fun start() { _cameraCommands.value = CameraCommand.Start }
    fun stop() { _cameraCommands.value = CameraCommand.Stop }

    fun onFrame(frame: FrameData) {
        // обработка кадра: ML/кодек/сеть — вне core
    }
}
```

---

## Встраивание: Activity XML

### Layout

```xml
<!-- res/layout/activity_camera.xml -->
<com.alezzgo.lunalab.core.camera.CameraXView
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

### Activity (imperative управление + сбор кадров)

```kotlin
class CameraActivity : AppCompatActivity(R.layout.activity_camera) {

    private val cameraView by lazy { findViewById<CameraXView>(R.id.cameraView) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        cameraView.setAutoStart(false)
        cameraView.setLensFacing(CameraSelector.LENS_FACING_BACK)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cameraView.frameFlow.collect { frame ->
                    onFrame(frame)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        cameraView.startCamera()
    }

    override fun onStop() {
        cameraView.stopCamera()
        super.onStop()
    }

    private fun onFrame(frame: FrameData) {
        // обработка кадра — вне core
    }
}
```

---

## Встраивание: Jetpack Compose через `AndroidView`

Ключевая идея: `CameraXView` — это обычный View, поэтому в Compose он живёт через `AndroidView`.  
Дальше вы выбираете: **reactive** управление (StateFlow команд) или **autoStart**.

### Compose (reactive управление + сбор кадров)

```kotlin
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    lensFacing: Int,
    commands: StateFlow<CameraCommand>,
    onFrame: (FrameData) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            CameraXView(ctx).also { view ->
                view.setAutoStart(false)
                view.setLensFacing(lensFacing)
                view.bindCommands(commands)
            }
        },
        update = { view ->
            // при смене facing ребиндится сам (внутри view)
            view.setLensFacing(lensFacing)
        }
    )

    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val cameraView = requireNotNull(cameraRef[0])
            cameraView.frameFlow.collect(onFrame)
        }
    }
}
```

---

## Рекомендованный паттерн слоя экрана (унификация для Fragment/Activity/Compose)

Чтобы интеграции были одинаковые для любого UI:

- Экран держит **ViewModel**, которая владеет:
  - `cameraCommands: StateFlow<CameraCommand>`
  - `onFrame(frame: FrameData)` (или делегирует в use-case)
- UI слой:
  - встраивает `CameraXView`,
  - биндит `commands` (если reactive),
  - собирает `frameFlow` и прокидывает в VM/use-case.

Этот паттерн гарантирует, что логика управления и обработки кадров живёт **вне** View и не зависит от конкретного UI-стека.


