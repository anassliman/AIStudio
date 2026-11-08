# تقرير المرحلة الأولى: قرارات المجال والعقود النهائية

## النطاق

نفّذت هذه الجولة تعديلات على نماذج `core` وعقوده واختباراته وهذا التقرير فقط. لا يوجد تنفيذ لـ Room أو AI Provider أو MCP أو نظام الصلاحيات، ولم تتغير واجهة Android أو `MainActivity` أو Manifest.

## قرارات النماذج ودورة الحياة

- `Message` يملك `sequence` ثابتاً داخل المحادثة. حالات الرسالة: `PENDING`, `SENDING`, `STREAMING`, `COMPLETED`, `FAILED`, `CANCELLED`, `INTERRUPTED`. تتطلب `FAILED` و`INTERRUPTED` خطأً، ولا يحمل `CANCELLED` خطأ فشل.
- حالة الأداة منفصلة لأنها تصف الأثر الخارجي: `PENDING_APPROVAL`, `APPROVED`, `RUNNING`, `COMPLETED`, `FAILED`, `DENIED`, `EXPIRED`, `CANCELLED`, `UNKNOWN_AFTER_RESTART`.
- يعامل `CancellationException` كإلغاء coroutine ويعاد رميه بعد محاولة حفظ `CANCELLED` في تنفيذ المحرك. لا يوجد `Cancelled` ضمن أخطاء المزوّد/المحادثة.
- عند استعادة الحالة: توليد مساعد متروك يصبح `INTERRUPTED`؛ أداة كانت `RUNNING` تصبح `UNKNOWN_AFTER_RESTART` ولا تُعاد تلقائياً؛ الموافقة المنتهية تصبح `EXPIRED`. الموافقة المقبولة التي لم تبدأ `RUNNING` بعد لا تستأنف إلا ما دامت صالحة.

## طلبات الأدوات والموافقات

- `ToolRequest` و`ToolResult` محايدان عن MCP والمزوّد. النتائج تميز النجاح والفشل التقني والرفض.
- `ToolFailureCode` يحدد: `UNKNOWN_TOOL`, `UNAVAILABLE`, `INVALID_ARGUMENTS`, `TIMED_OUT`, `EXECUTION_FAILED`, `OUTPUT_LIMIT_EXCEEDED`.
- `RequestFingerprint.calculate` في `core` ينشئ SHA-256 من ترميز ثابت ذي حقول UTF-8 مسبوقة بالطول، وبترتيب وإصدار ثابتين: `conversationId`, `toolCallId`, `toolId`, `canonicalJson` للوسائط. لا يقبل `resolveApproval` بصمة من الواجهة؛ يحمل سجل الموافقة الطلب الأصلي، ويعيد المنسق حساب البصمة ومقارنتها قبل الاستهلاك. `fromPersisted` يعيد قراءة صيغة البصمة فقط، ولا يغني عن إعادة الحساب.
- يجب أن تكون `ToolArguments.canonicalJson` تمثيلاً معيارياً للوسائط. البصمة تحقق التطابق مع الطلب المسجل؛ ليست توقيعاً تشفيرياً لمقاومة تعديل قاعدة بيانات مخترقة.
- الموافقة مرتبطة بمعرّف المحادثة والاستدعاء وبصمته ووقت انتهاء. عقد `consumeApproval` يطلب من التنفيذ التحقق من هذه القيم ومن ساعة موثوقة، ثم تغيير الحالة ذرياً مرة واحدة. قيد فريد للاستدعاء وCAS من `APPROVED` إلى `RUNNING` يمنعان إعادة الإرسال من التطبيق. لا يمكن ضمان الأثر الخارجي مرة واحدة بعد انهيار بين التنفيذ والحفظ ما لم يدعم المنفّذ الخارجي مفتاح idempotency؛ تسجل الحالة حينها `UNKNOWN_AFTER_RESTART`.
- القيم الافتراضية لحدود المحرك: 6 جولات، 4 استدعاءات في الجولة، 12 استدعاء في الدور، 64 KiB للنتيجة الواحدة، و256 KiB إجمالاً. التنفيذ الأول متسلسل. الحدود يفرضها المحرك ولا يرسلها المستخدم في `SendMessageRequest`.

## اتساق التخزين والترتيب

- `beginTurn` في العقد عملية واحدة: حفظ رسالة المستخدم والمساعد المبدئية وتحديث المحادثة وحجز رقمين متتاليين للرسالتين.
- `appendMessage` يحجز الرقم التالي لرسالة أداة/نتيجة. يجب أن يتم الحجز والإدراج داخل معاملة كتابة واحدة، مع قيد فريد `(conversationId, sequence)`. التنفيذ يجب أن يكتسب حق الكتابة قبل قراءة العداد ثم يزيده ويدرج السجلات في المعاملة نفسها.
- اختبار التزامن الحالي يستخدم مستودعاً وهمياً يحرس العداد بـ `Mutex` ويتحقق من أرقام متتابعة وفريدة لطلبات متزامنة. هذا يختبر شرط العقد فقط؛ لا يثبت معاملات SQLite. يلزم اختبار تكامل على مستودع Room عند تنفيذه، بما فيه التزامن/قيد التفرد والتراجع عند الإلغاء.
- عمليات تحديث حالة المساعد وإنهاء الدور والحفظ والاستعادة موضحة في `ConversationRepository`. استعادة البيانات تعتمد على ساعة تنفيذ التخزين، لا وقت يرسله المستدعي.

## المزوّدون والاستقلال

أُضيف `ProviderResolver` لتمييز المزوّد غير المسجل أو غير المتاح عن النموذج غير المدعوم، من دون فحص شبكة أو أسرار. `AIProvider` يعيد إجابة نهائية أو طلب أدوات معيارياً، والمحرك يعيد استدعاء المزوّد بالسجل المرتب بعد حفظ نتائج الأدوات. لا يعتمد `core` على Android أو Compose أو Room أو HTTP أو MCP أو FreeLLMAPI أو Gemini أو Codex.

## التبعيات والتوافق

لا توجد تبعية تشغيلية جديدة في `core`. أضيف `kotlinx-coroutines-test:1.11.0` ضمن `testImplementation` فقط لتشغيل اختبارات `runTest` والتزامن. إصدار coroutines 1.11.0 مبني مع Kotlin 2.2.20، وتوثيق K2 يذكر أن Kotlin/JVM يعمل مع مكتبات منشأة بأي إصدار Kotlin؛ مع ذلك لم يُتحقق من التجميع الفعلي في هذه البيئة.

- [إصدارات kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines/releases)
- [توافق K2 مع مكتبات Kotlin/JVM](https://kotlinlang.org/docs/k2-compiler-migration-guide.html#compatibility-with-kotlin-libraries)
- [واجهة kotlinx-coroutines-test](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-test/)

## الاختبارات

أصبحت هناك 26 حالة اختبار مصدرية. تغطي قيود النماذج والأدوار، نسخ القوائم، ترتيب سجل المزوّد ورفض الرسائل الجارية، بصمة الطلب واتساقها مع سجل التنفيذ، ربط الموافقة وانتهائها واستهلاكها مرة واحدة ورفضها، منع المطالبات المتزامنة للتنفيذ، حالات النجاح والفشل والإلغاء، الحدود، ترتيب الرسائل تحت طلبات متزامنة، واستعادة `INTERRUPTED` مقابل `UNKNOWN_AFTER_RESTART`. اختبارات الإلغاء هنا تتحقق من تخزين الحالة النهائية؛ لا يوجد تنفيذ لمحرك فعلي لاختبار انتشار `CancellationException` من المزوّد حتى المستودع.

اختبارات المستودع والموافقة تستخدم fixture داخل الذاكرة؛ تختبر العقد فقط ولا تثبت معاملات SQLite. ستحتاج Room إلى اختبارات تكامل على قاعدة فعلية، تشمل التزامن وقيد تفرد `(conversationId, sequence)` والتراجع عند الإلغاء. لا توجد بعد تطبيقات لمحرك المحادثة أو مزود أو Room.

## مراجعة وإصلاحات التحقق

- يرفض `ToolInvocation` الآن أي بصمة لا تطابق حساباً جديداً للطلب، ويتحقق من أن سجل الموافقة يطابق المحادثة والطلب والبصمة. يظل سجل `PendingToolApproval` قابلاً لكشف اختلاف الطلب عبر `validateFor`.
- يوضح عقد `consumeApproval` أن التنفيذ يعيد حساب البصمة من الطلب المخزن ولا يقبلها من المستدعي. أضيفت ضمانات fixture بأن انتقال حالة الأداة لا يستطيع استبدال الطلب أو الموافقة الأصليين.
- الاستعادة في fixture لا تغيّر رسائل `SYSTEM` أو `USER` إلى `INTERRUPTED`؛ تقتصر على رسائل المساعد المتروكة في `SENDING` أو `STREAMING`.
- أضيفت خطوة `gradle :core:test` إلى GitHub Actions بعد تجهيز Gradle وقبل بناء APK. تبقى مهمة `:app:assembleDebug` ورفع الـAPK دون تغيير.
- إعدادات المشروع تستخدم Kotlin JVM وCompose plugin `2.4.21` وGradle `9.5.0`، وتستخدم `kotlinx-coroutines-test:1.11.0` في `testImplementation` فقط؛ لا توجد تبعيات تشغيلية جديدة لـ`core`. مصفوفة Kotlin الرسمية تشمل Gradle 9.5 ضمن مدى التوافق المعلن لإصدارات 2.4.x، ويشرح دليل K2 توافق مكتبات Kotlin على JVM. لا يمكن تأكيد تجميع هذه المجموعة في الجهاز دون تشغيل Gradle.
- لم يُضف Gradle Wrapper: CI يجهز Gradle 9.5.0 بالفعل، ولا يتوفر Gradle محلي لإنشاء Wrapper أو تشغيله، وإنشاء/تشغيل Wrapper غير مطلوب للتحقق على CI.

**نتيجة تشغيل الاختبارات في هذه الجولة:** لم يمكن تشغيلها محلياً لعدم توفر `gradle` أو Gradle Wrapper. لم تُثبت أدوات. يظل تشغيل `:core:test` في بيئة CI ذات Gradle المُجهّز مطلوباً لإثبات نجاح التجميع والتنفيذ.

## الملفات

**معدّلة في جولة التأسيس:** `core/build.gradle.kts`; العقود والنماذج الموجودة تحت `core/src/main/kotlin/com/aistudio/core`; `DomainModelsTest.kt`; `ContractTypesTest.kt`; وهذا التقرير.

**منشأة:** `ImmutableSnapshots.kt`, `ToolModels.kt`, `ProviderResolver.kt`, `ToolCoordinator.kt`, `ApprovalAndConcurrencyContractTest.kt`, `ContractRepositoryFixture.kt`.

**تغييرات جولة المراجعة:** تحديث `ToolModels.kt`, `ConversationRepository.kt`, `ContractRepositoryFixture.kt`, `ApprovalAndConcurrencyContractTest.kt`, `DomainModelsTest.kt`، وإضافة خطوة اختبار core إلى `.github/workflows/android-debug.yml`.

بقيت `MainActivity.kt` وManifest دون تغيير. لم يُنفذ Commit أو Push.
