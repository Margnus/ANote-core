#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class NCPAdmissionRejection, NCPAdmissionResultAdmitted, NCPAdmissionResultExisting, NCPAdmissionResultRejected, NCPAffine2D, NCPAppleBrushJobExperiment, NCPAppleBrushReaderExperiment, NCPAppleBrushResultExperiment, NCPApplePreviewInput, NCPBoundsD, NCPBrushBlendMode, NCPBrushFillRule, NCPBrushGeometry, NCPBrushGeometryBudget, NCPBrushGeometryBuilder, NCPBrushGeometryRejection, NCPBrushGeometryResultReady, NCPBrushGeometryResultRejected, NCPBrushKind, NCPBrushPathPlan, NCPBrushPathPlanResultReady, NCPBrushPathPlanResultRejected, NCPBrushPathPlanner, NCPBrushPathPrecision, NCPBrushPathRejection, NCPBrushSpec, NCPCacheAdmissionStored<K, V>, NCPCanvasCommandAddStroke, NCPCanvasCommandDeleteStroke, NCPCanvasCommandTransformStroke, NCPCommandId, NCPCommitReceipt, NCPCommitResultAccepted, NCPCommitResultRejected, NCPCompletedInput, NCPCompletionUpdate, NCPDispatchResultAttemptNumbersExhausted, NCPDispatchResultBlocked, NCPDispatchResultBusy, NCPDispatchResultNoWork, NCPDispatchResultReady, NCPDispatchResultRetryRequired, NCPDisplacedEntry<K, V>, NCPDocumentAuthorityStatus, NCPDocumentBudget, NCPDocumentBudgetCompanion, NCPDocumentBudgetCreation, NCPDocumentBudgetFailure, NCPDocumentClosePhase, NCPDocumentCloseStatus, NCPDocumentCommandCanvas, NCPDocumentCommandDeletePage, NCPDocumentCommandInsertPage, NCPDocumentCommandMovePage, NCPDocumentCommandRedo, NCPDocumentCommandRename, NCPDocumentCommandSetPaper, NCPDocumentCommandUndo, NCPDocumentCommitAccepted, NCPDocumentCommitRejected, NCPDocumentReceipt, NCPDocumentRejection, NCPDocumentRoot, NCPDocumentRootCompanion, NCPDocumentSession, NCPDurability, NCPFrameToken, NCPGeometryCacheKey, NCPInputBatch, NCPInputRejection, NCPInputReplaySessionCompanion, NCPInputResultCancelled, NCPInputResultCompleted, NCPInputResultIgnoredNavigation, NCPInputResultPreview, NCPInputResultRejected, NCPInputSample, NCPKotlinArray<T>, NCPKotlinDoubleArray, NCPKotlinDoubleIterator, NCPKotlinEnum<E>, NCPKotlinEnumCompanion, NCPKotlinException, NCPKotlinFloatArray, NCPKotlinFloatIterator, NCPKotlinLongArray, NCPKotlinLongIterator, NCPKotlinPair<__covariant A, __covariant B>, NCPKotlinRuntimeException, NCPKotlinThrowable, NCPLayerId, NCPLeaseReturn, NCPObjectChange, NCPObjectId, NCPOwnedWorkStage, NCPOwnedWorkSummary, NCPOwnerExecutorStatus, NCPOwnerWorkTicket, NCPPageDescriptor, NCPPageId, NCPPagedPreviewCodec, NCPPagedPreviewHeader, NCPPagedPreviewNotebook, NCPPagedPreviewNotebookCompanion, NCPPagedPreviewSnapshot, NCPPagedPreviewSnapshotCompanion, NCPPaperKind, NCPPointD, NCPPointerPhase, NCPPointerTool, NCPPrepareResultPrepared, NCPPrepareResultRejected, NCPPreviewCatalogBudget, NCPPreviewDocumentRead, NCPPreviewDocumentReader, NCPPreviewEditCopyPage, NCPPreviewEditDeletePage, NCPPreviewEditInsertPage, NCPPreviewEditMovePage, NCPPreviewEditRedo, NCPPreviewEditRename, NCPPreviewEditResult, NCPPreviewEditSelectPage, NCPPreviewEditSelectPen, NCPPreviewEditSetPaper, NCPPreviewEditUndo, NCPPreviewFailureKind, NCPPreviewInputDisposition, NCPPreviewInputResult, NCPPreviewNotebookOpen, NCPPreviewOpenFailure, NCPPreviewReadFailure, NCPPreviewRecoveryCodec, NCPPreviewRecoveryRead, NCPPreviewSaveAdmission, NCPPreviewSaveAttempt, NCPPreviewSaveState, NCPPreviewSessionFailure, NCPPreviewWriteAdmission, NCPPreviewWriteDisposition, NCPRecoveryOffer, NCPRecoveryPayloadBlocked, NCPRecoveryPayloadConfirmed, NCPRecoveryPayloadPending, NCPRejection, NCPReservationFailure, NCPReservationResultRejected, NCPReservationResultReserved, NCPResourceBorrowBorrowed<V>, NCPResourceBorrowClosed, NCPResourceBorrowLeaseLimit, NCPResourceBorrowMissing, NCPResourceCompletion, NCPResourceDrainStatus, NCPResourceHandle, NCPResourceLane, NCPResourceLease<V>, NCPResourcePublicationAlreadyOwned, NCPResourcePublicationResident, NCPResourcePublicationRetired, NCPResourcePublicationRetiredAfterClose, NCPResourcePublicationStaleReservation, NCPResourcePublicationUnsettledReservation, NCPResourceReleaseDispatchIdle, NCPResourceReleaseDispatchReady<V>, NCPResourceReleaseDispatchWrongLane, NCPResourceReleaseOutcome, NCPResourceReleaseTicket<V>, NCPResourceReservation, NCPResultDisposition, NCPResultDrain, NCPSceneApplicationApplied, NCPSceneApplicationRejected, NCPSceneId, NCPScenePatch, NCPSceneReducer, NCPSceneSnapshot, NCPSceneSnapshotCompanion, NCPSessionInputFailure, NCPSessionInputResultConsumed, NCPSessionInputResultRejected, NCPSessionOpenFailure, NCPSessionOpenResultOpened, NCPSessionOpenResultRejected, NCPSessionStop, NCPStrokeObject, NCPStrokeSamples, NCPStrokeSamplesCompanion, NCPStrokeSubmission, NCPStrokeTarget, NCPSubmissionAttempt, NCPSubmissionStateBlocked, NCPSubmissionStateConfirmed, NCPSubmissionStateInFlight, NCPSubmissionStateQueued, NCPSubmissionStateRetryRequired, NCPSubmissionSummary, NCPWorkAdmissionAccepted, NCPWorkAdmissionFailure, NCPWorkAdmissionRejected, NCPWorkResultNotExecuted, NCPWorkResultOutcomeUnknown, NCPWorkResultSucceeded<R>, NSData;

@protocol NCPAdmissionResult, NCPBrushGeometryResult, NCPBrushPathPlanResult, NCPCacheAdmission, NCPCanvasCommand, NCPCommitResult, NCPDispatchResult, NCPDocumentCommand, NCPDocumentCommit, NCPInputResult, NCPKotlinComparable, NCPKotlinIterator, NCPPrepareResult, NCPPreviewEdit, NCPPreviewSaveSnapshot, NCPRecoveryPayload, NCPReservationResult, NCPResourceBorrow, NCPResourcePublication, NCPResourceReleaseDispatch, NCPSceneApplication, NCPSessionInputResult, NCPSessionOpenResult, NCPSubmissionState, NCPWorkAdmission, NCPWorkResult;

NS_ASSUME_NONNULL_BEGIN
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wunknown-warning-option"
#pragma clang diagnostic ignored "-Wincompatible-property-type"
#pragma clang diagnostic ignored "-Wnullability"

#pragma push_macro("_Nullable_result")
#if !__has_feature(nullability_nullable_result)
#undef _Nullable_result
#define _Nullable_result _Nullable
#endif

__attribute__((swift_name("KotlinBase")))
@interface NCPBase : NSObject
- (instancetype)init __attribute__((unavailable));
+ (instancetype)new __attribute__((unavailable));
@end

@interface NCPBase (NCPBaseCopying) <NSCopying>
@end





@interface NSError (NSErrorNCPKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

























__attribute__((swift_name("KotlinComparable")))
@protocol NCPKotlinComparable
@required
@end

__attribute__((swift_name("KotlinEnum")))
@interface NCPKotlinEnum<E> : NCPBase <NCPKotlinComparable>
@end

































__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Affine2D")))
@interface NCPAffine2D : NCPBase








@property (readonly) double a __attribute__((swift_name("a")));
@property (readonly) double b __attribute__((swift_name("b")));
@property (readonly) double c __attribute__((swift_name("c")));
@property (readonly) double d __attribute__((swift_name("d")));
@property (readonly) double tx __attribute__((swift_name("tx")));
@property (readonly) double ty __attribute__((swift_name("ty")));
@end





















































__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushSpec")))
@interface NCPBrushSpec : NCPBase
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) double width __attribute__((swift_name("width")));
@end









__attribute__((swift_name("CanvasCommand")))
@protocol NCPCanvasCommand
@required
@end

































































__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputRejection")))
@interface NCPInputRejection : NCPKotlinEnum<NCPInputRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPInputRejection *closed __attribute__((swift_name("closed")));
@property (class, readonly) NCPInputRejection *eventOrder __attribute__((swift_name("eventOrder")));
@property (class, readonly) NCPInputRejection *pointerBusy __attribute__((swift_name("pointerBusy")));
@property (class, readonly) NCPInputRejection *noActivePointer __attribute__((swift_name("noActivePointer")));
@property (class, readonly) NCPInputRejection *invalidBatch __attribute__((swift_name("invalidBatch")));
@property (class, readonly) NCPInputRejection *capacity __attribute__((swift_name("capacity")));
@property (class, readonly) NSArray<NCPInputRejection *> *entries __attribute__((swift_name("entries")));
@end























__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("LayerId")))
@interface NCPLayerId : NCPBase
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

























__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PointerPhase")))
@interface NCPPointerPhase : NCPKotlinEnum<NCPPointerPhase *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPointerPhase *down __attribute__((swift_name("down")));
@property (class, readonly) NCPPointerPhase *move __attribute__((swift_name("move")));
@property (class, readonly) NCPPointerPhase *up __attribute__((swift_name("up")));
@property (class, readonly) NCPPointerPhase *cancel __attribute__((swift_name("cancel")));
@property (class, readonly) NSArray<NCPPointerPhase *> *entries __attribute__((swift_name("entries")));
@end































































































__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneId")))
@interface NCPSceneId : NCPBase
@end





























__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeObject")))
@interface NCPStrokeObject : NCPBase
@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@property (readonly) NCPStrokeSamples *samples __attribute__((swift_name("samples")));
@property (readonly) NCPAffine2D *transform __attribute__((swift_name("transform")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeSamples")))
@interface NCPStrokeSamples : NCPBase
- (float)pressureIndex:(int32_t)index __attribute__((swift_name("pressure(index:)")));
- (int64_t)timeMicrosIndex:(int32_t)index __attribute__((swift_name("timeMicros(index:)")));
- (double)xIndex:(int32_t)index __attribute__((swift_name("x(index:)")));
- (double)yIndex:(int32_t)index __attribute__((swift_name("y(index:)")));


@property (readonly) int32_t size __attribute__((swift_name("size")));
@end













__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeTarget")))
@interface NCPStrokeTarget : NCPBase






@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@end















































__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ApplePreviewInput")))
@interface NCPApplePreviewInput : NCPBase
+ (instancetype)alloc __attribute__((unavailable));


+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPApplePreviewInput *shared __attribute__((swift_name("shared")));
- (NCPPreviewInputResult *)offerNotebook:(NCPPagedPreviewNotebook *)notebook phase:(NCPPointerPhase *)phase eventTimeMicros:(int64_t)eventTimeMicros xs:(NSData *)xs ys:(NSData *)ys pressures:(NSData *)pressures times:(NSData *)times __attribute__((swift_name("offer(notebook:phase:eventTimeMicros:xs:ys:pressures:times:)")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudget")))
@interface NCPDocumentBudget : NCPBase
- (instancetype)initWithMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes __attribute__((swift_name("init(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPDocumentBudgetCompanion *companion __attribute__((swift_name("companion")));


@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudget.Companion")))
@interface NCPDocumentBudgetCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));


- (NCPDocumentBudgetCreation *)validatedMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes __attribute__((swift_name("validated(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudgetCreation")))
@interface NCPDocumentBudgetCreation : NCPBase
@property (readonly) NCPDocumentBudget * _Nullable budget __attribute__((swift_name("budget")));
@property (readonly) NCPDocumentBudgetFailure * _Nullable failure __attribute__((swift_name("failure")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudgetFailure")))
@interface NCPDocumentBudgetFailure : NCPKotlinEnum<NCPDocumentBudgetFailure *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPDocumentBudgetFailure *maxPages __attribute__((swift_name("maxPages")));
@property (class, readonly) NCPDocumentBudgetFailure *maxObjects __attribute__((swift_name("maxObjects")));
@property (class, readonly) NCPDocumentBudgetFailure *liveBytes __attribute__((swift_name("liveBytes")));
@property (class, readonly) NCPDocumentBudgetFailure *journalEntries __attribute__((swift_name("journalEntries")));
@property (class, readonly) NCPDocumentBudgetFailure *journalBytes __attribute__((swift_name("journalBytes")));
@property (class, readonly) NCPDocumentBudgetFailure *identityBytes __attribute__((swift_name("identityBytes")));
@property (class, readonly) NCPDocumentBudgetFailure *historyEntries __attribute__((swift_name("historyEntries")));
@property (class, readonly) NCPDocumentBudgetFailure *historyBytes __attribute__((swift_name("historyBytes")));
@property (class, readonly) NCPDocumentBudgetFailure *inputBytes __attribute__((swift_name("inputBytes")));
@property (class, readonly) NSArray<NCPDocumentBudgetFailure *> *entries __attribute__((swift_name("entries")));
@end



__attribute__((swift_name("DocumentCommand")))
@protocol NCPDocumentCommand
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandCanvas")))
@interface NCPDocumentCommandCanvas : NCPBase <NCPDocumentCommand>
@property (readonly) id<NCPCanvasCommand> command __attribute__((swift_name("command")));
@end





























__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentRejection")))
@interface NCPDocumentRejection : NCPKotlinEnum<NCPDocumentRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPDocumentRejection *wrongDocument __attribute__((swift_name("wrongDocument")));
@property (class, readonly) NCPDocumentRejection *staleRoot __attribute__((swift_name("staleRoot")));
@property (class, readonly) NCPDocumentRejection *revisionExhausted __attribute__((swift_name("revisionExhausted")));
@property (class, readonly) NCPDocumentRejection *missingPage __attribute__((swift_name("missingPage")));
@property (class, readonly) NCPDocumentRejection *wrongLayer __attribute__((swift_name("wrongLayer")));
@property (class, readonly) NCPDocumentRejection *stalePage __attribute__((swift_name("stalePage")));
@property (class, readonly) NCPDocumentRejection *reusedOperationId __attribute__((swift_name("reusedOperationId")));
@property (class, readonly) NCPDocumentRejection *reusedIdentity __attribute__((swift_name("reusedIdentity")));
@property (class, readonly) NCPDocumentRejection *theCopyMapping __attribute__((swift_name("theCopyMapping")));
@property (class, readonly) NCPDocumentRejection *invalidPage __attribute__((swift_name("invalidPage")));
@property (class, readonly) NCPDocumentRejection *invalidTitle __attribute__((swift_name("invalidTitle")));
@property (class, readonly) NCPDocumentRejection *sceneRejected __attribute__((swift_name("sceneRejected")));
@property (class, readonly) NCPDocumentRejection *noChange __attribute__((swift_name("noChange")));
@property (class, readonly) NCPDocumentRejection *pageCapacity __attribute__((swift_name("pageCapacity")));
@property (class, readonly) NCPDocumentRejection *objectCapacity __attribute__((swift_name("objectCapacity")));
@property (class, readonly) NCPDocumentRejection *liveByteCapacity __attribute__((swift_name("liveByteCapacity")));
@property (class, readonly) NCPDocumentRejection *journalCapacity __attribute__((swift_name("journalCapacity")));
@property (class, readonly) NCPDocumentRejection *identityCapacity __attribute__((swift_name("identityCapacity")));
@property (class, readonly) NCPDocumentRejection *historyEmpty __attribute__((swift_name("historyEmpty")));
@property (class, readonly) NCPDocumentRejection *historyTarget __attribute__((swift_name("historyTarget")));
@property (class, readonly) NCPDocumentRejection *historyConflict __attribute__((swift_name("historyConflict")));
@property (class, readonly) NCPDocumentRejection *historyCapacity __attribute__((swift_name("historyCapacity")));
@property (class, readonly) NCPDocumentRejection *closed __attribute__((swift_name("closed")));
@property (class, readonly) NCPDocumentRejection *invalidIdentity __attribute__((swift_name("invalidIdentity")));
@property (class, readonly) NSArray<NCPDocumentRejection *> *entries __attribute__((swift_name("entries")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentRoot")))
@interface NCPDocumentRoot : NCPBase
@property (readonly) NCPSceneId *id __attribute__((swift_name("id")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int64_t revision __attribute__((swift_name("revision")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@end







__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PageDescriptor")))
@interface NCPPageDescriptor : NCPBase
@property (readonly) int64_t contentRevision __attribute__((swift_name("contentRevision")));
@property (readonly) double height __attribute__((swift_name("height")));
@property (readonly) NCPPageId *id __attribute__((swift_name("id")));
@property (readonly) NCPLayerId *layerId __attribute__((swift_name("layerId")));
@property (readonly) NCPPaperKind *paper __attribute__((swift_name("paper")));
@property (readonly) double width __attribute__((swift_name("width")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PageId")))
@interface NCPPageId : NCPBase
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end









__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewNotebook")))
@interface NCPPagedPreviewNotebook : NCPBase


@property (class, readonly, getter=companion) NCPPagedPreviewNotebookCompanion *companion __attribute__((swift_name("companion")));
- (void)cancelStroke __attribute__((swift_name("cancelStroke()")));


- (NCPPagedPreviewSnapshot *)capture __attribute__((swift_name("capture()")));


- (NCPPagedPreviewSnapshot *)captureRecovery __attribute__((swift_name("captureRecovery()")));


- (NCPPreviewEditResult *)editRequest:(id<NCPPreviewEdit>)request __attribute__((swift_name("edit(request:)")));
- (NCPPageDescriptor *)pageIndex:(int32_t)index __attribute__((swift_name("page(index:)")));


- (NCPDocumentCommandCanvas * _Nullable)pendingStroke __attribute__((swift_name("pendingStroke()")));
- (int32_t)pointCountIndex:(int32_t)index __attribute__((swift_name("pointCount(index:)")));
- (float)pointPressureIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointPressure(index:point:)")));
- (double)pointXIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointX(index:point:)")));
- (double)pointYIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointY(index:point:)")));


- (NCPPreviewInputResult * _Nullable)prepareRealBatchPhase:(NCPPointerPhase *)phase count:(int32_t)count __attribute__((swift_name("prepareRealBatch(phase:count:)")));


- (BOOL)retire __attribute__((swift_name("retire()")));


- (BOOL)retireAfterRecoverySnapshot:(NCPPagedPreviewSnapshot *)snapshot __attribute__((swift_name("retireAfterRecovery(snapshot:)")));
- (BOOL)retryPendingStroke __attribute__((swift_name("retryPendingStroke()")));


- (BOOL)setReadOnlyValue:(BOOL)value __attribute__((swift_name("setReadOnly(value:)")));
- (NCPDocumentRoot *)snapshot __attribute__((swift_name("snapshot()")));
- (NCPStrokeObject *)strokeIndex:(int32_t)index __attribute__((swift_name("stroke(index:)")));


@property (readonly) NSString * _Nullable activeInputOperationId __attribute__((swift_name("activeInputOperationId")));
@property (readonly) int64_t changeVersion __attribute__((swift_name("changeVersion")));
@property (readonly) NCPPageDescriptor *currentPage __attribute__((swift_name("currentPage")));
@property (readonly) BOOL hasActiveStroke __attribute__((swift_name("hasActiveStroke")));
@property (readonly) BOOL hasPendingStroke __attribute__((swift_name("hasPendingStroke")));
@property (readonly) int32_t inputBatchLimit __attribute__((swift_name("inputBatchLimit")));


@property (readonly) int64_t inputReservedByteCharge __attribute__((swift_name("inputReservedByteCharge")));
@property (readonly) BOOL isReadOnly __attribute__((swift_name("isReadOnly")));
@property (readonly) NCPPreviewSessionFailure * _Nullable lastFailure __attribute__((swift_name("lastFailure")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int32_t strokeCount __attribute__((swift_name("strokeCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewNotebook.Companion")))
@interface NCPPagedPreviewNotebookCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));




- (NCPPreviewNotebookOpen *)createValidatedDocumentId:(NSString *)documentId title:(NSString *)title sessionNonce:(NSString *)sessionNonce width:(double)width height:(double)height paper:(NCPPaperKind *)paper budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("createValidated(documentId:title:sessionNonce:width:height:paper:budget:ownerCheck:)")));


- (NCPPreviewNotebookOpen *)openInitial:(NCPDocumentRoot *)initial sessionNonce:(NSString *)sessionNonce budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("open(initial:sessionNonce:budget:ownerCheck:)")));


- (NCPPreviewNotebookOpen *)openRecoverySnapshot:(NCPPagedPreviewSnapshot *)snapshot sessionNonce:(NSString *)sessionNonce budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("openRecovery(snapshot:sessionNonce:budget:ownerCheck:)")));
@end



__attribute__((swift_name("PreviewSaveSnapshot")))
@protocol NCPPreviewSaveSnapshot
@required




@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewSnapshot")))
@interface NCPPagedPreviewSnapshot : NCPBase <NCPPreviewSaveSnapshot>


- (NSString * _Nullable)encodeAndReturnError:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encode()")));
- (BOOL)sameContentOther:(id<NCPPreviewSaveSnapshot>)other __attribute__((swift_name("sameContent(other:)")));
@property (readonly) NSString *documentId __attribute__((swift_name("documentId")));
@property (readonly) int64_t encodingWorkByteCharge __attribute__((swift_name("encodingWorkByteCharge")));
@property (readonly) NCPDocumentCommandCanvas * _Nullable pending __attribute__((swift_name("pending")));
@property (readonly) int64_t retainedByteCharge __attribute__((swift_name("retainedByteCharge")));
@property (readonly) NCPDocumentRoot *root __attribute__((swift_name("root")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@property (readonly) int64_t version __attribute__((swift_name("version")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PaperKind")))
@interface NCPPaperKind : NCPKotlinEnum<NCPPaperKind *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPaperKind *blank __attribute__((swift_name("blank")));
@property (class, readonly) NCPPaperKind *ruled __attribute__((swift_name("ruled")));
@property (class, readonly) NCPPaperKind *grid __attribute__((swift_name("grid")));
@property (class, readonly) NCPPaperKind *dots __attribute__((swift_name("dots")));
@property (class, readonly) NSArray<NCPPaperKind *> *entries __attribute__((swift_name("entries")));
@end





__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewDocumentRead")))
@interface NCPPreviewDocumentRead : NCPBase
@property (readonly) NCPPreviewReadFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPDocumentRoot * _Nullable root __attribute__((swift_name("root")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewDocumentReader")))
@interface NCPPreviewDocumentReader : NCPBase
+ (instancetype)alloc __attribute__((unavailable));


+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewDocumentReader *shared __attribute__((swift_name("shared")));


- (NCPPreviewDocumentRead *)acceptRoot:(NCPDocumentRoot *)root __attribute__((swift_name("accept(root:)")));
- (NCPPreviewDocumentRead *)readText:(NSString *)text __attribute__((swift_name("read(text:)")));
@end



__attribute__((swift_name("PreviewEdit")))
@protocol NCPPreviewEdit
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditCopyPage")))
@interface NCPPreviewEditCopyPage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewEditCopyPage *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditDeletePage")))
@interface NCPPreviewEditDeletePage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewEditDeletePage *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditInsertPage")))
@interface NCPPreviewEditInsertPage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewEditInsertPage *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditMovePage")))
@interface NCPPreviewEditMovePage : NCPBase <NCPPreviewEdit>
- (instancetype)initWithBeforePageId:(NSString * _Nullable)beforePageId __attribute__((swift_name("init(beforePageId:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditRedo")))
@interface NCPPreviewEditRedo : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewEditRedo *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditRename")))
@interface NCPPreviewEditRename : NCPBase <NCPPreviewEdit>
- (instancetype)initWithTitle:(NSString *)title __attribute__((swift_name("init(title:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSelectPage")))
@interface NCPPreviewEditSelectPage : NCPBase <NCPPreviewEdit>
- (instancetype)initWithPageId:(NSString *)pageId __attribute__((swift_name("init(pageId:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSelectPen")))
@interface NCPPreviewEditSelectPen : NCPBase <NCPPreviewEdit>
- (instancetype)initWithArgb:(int32_t)argb width:(double)width __attribute__((swift_name("init(argb:width:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSetPaper")))
@interface NCPPreviewEditSetPaper : NCPBase <NCPPreviewEdit>
- (instancetype)initWithWidth:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("init(width:height:paper:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditUndo")))
@interface NCPPreviewEditUndo : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewEditUndo *shared __attribute__((swift_name("shared")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditResult")))
@interface NCPPreviewEditResult : NCPBase
@property (readonly) BOOL accepted __attribute__((swift_name("accepted")));
@property (readonly) NCPPreviewSessionFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) int64_t rootRevision __attribute__((swift_name("rootRevision")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewFailureKind")))
@interface NCPPreviewFailureKind : NCPKotlinEnum<NCPPreviewFailureKind *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewFailureKind *closed __attribute__((swift_name("closed")));
@property (class, readonly) NCPPreviewFailureKind *pendingStroke __attribute__((swift_name("pendingStroke")));
@property (class, readonly) NCPPreviewFailureKind *activeStroke __attribute__((swift_name("activeStroke")));
@property (class, readonly) NCPPreviewFailureKind *invalidArgument __attribute__((swift_name("invalidArgument")));
@property (class, readonly) NCPPreviewFailureKind *missingPage __attribute__((swift_name("missingPage")));
@property (class, readonly) NCPPreviewFailureKind *capacity __attribute__((swift_name("capacity")));
@property (class, readonly) NCPPreviewFailureKind *identityExhausted __attribute__((swift_name("identityExhausted")));
@property (class, readonly) NCPPreviewFailureKind *historyEmpty __attribute__((swift_name("historyEmpty")));
@property (class, readonly) NCPPreviewFailureKind *documentRejected __attribute__((swift_name("documentRejected")));
@property (class, readonly) NCPPreviewFailureKind *noActiveStroke __attribute__((swift_name("noActiveStroke")));
@property (class, readonly) NCPPreviewFailureKind *invalidInput __attribute__((swift_name("invalidInput")));
@property (class, readonly) NCPPreviewFailureKind *inputBatchCapacity __attribute__((swift_name("inputBatchCapacity")));
@property (class, readonly) NCPPreviewFailureKind *readOnly __attribute__((swift_name("readOnly")));
@property (class, readonly) NSArray<NCPPreviewFailureKind *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewInputDisposition")))
@interface NCPPreviewInputDisposition : NCPKotlinEnum<NCPPreviewInputDisposition *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewInputDisposition *rejected __attribute__((swift_name("rejected")));
@property (class, readonly) NCPPreviewInputDisposition *accepted __attribute__((swift_name("accepted")));
@property (class, readonly) NCPPreviewInputDisposition *committed __attribute__((swift_name("committed")));
@property (class, readonly) NCPPreviewInputDisposition *pendingCommit __attribute__((swift_name("pendingCommit")));
@property (class, readonly) NCPPreviewInputDisposition *cancelled __attribute__((swift_name("cancelled")));
@property (class, readonly) NSArray<NCPPreviewInputDisposition *> *entries __attribute__((swift_name("entries")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewInputResult")))
@interface NCPPreviewInputResult : NCPBase
@property (readonly) int32_t acceptedSamples __attribute__((swift_name("acceptedSamples")));
@property (readonly) int32_t activeSamples __attribute__((swift_name("activeSamples")));
@property (readonly) BOOL admitted __attribute__((swift_name("admitted")));
@property (readonly) NCPPreviewInputDisposition *disposition __attribute__((swift_name("disposition")));
@property (readonly) NCPPreviewSessionFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPInputRejection * _Nullable inputReason __attribute__((swift_name("inputReason")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewNotebookOpen")))
@interface NCPPreviewNotebookOpen : NCPBase
@property (readonly) NCPDocumentRejection * _Nullable documentReason __attribute__((swift_name("documentReason")));
@property (readonly) NCPPreviewOpenFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPPagedPreviewNotebook * _Nullable notebook __attribute__((swift_name("notebook")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewOpenFailure")))
@interface NCPPreviewOpenFailure : NCPKotlinEnum<NCPPreviewOpenFailure *>
+ (instancetype)alloc __attribute__((unavailable));


+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewOpenFailure *invalidSessionNonce __attribute__((swift_name("invalidSessionNonce")));
@property (class, readonly) NCPPreviewOpenFailure *unsupportedBudget __attribute__((swift_name("unsupportedBudget")));
@property (class, readonly) NCPPreviewOpenFailure *invalidContent __attribute__((swift_name("invalidContent")));
@property (class, readonly) NCPPreviewOpenFailure *unsupportedContent __attribute__((swift_name("unsupportedContent")));
@property (class, readonly) NCPPreviewOpenFailure *documentCapacity __attribute__((swift_name("documentCapacity")));
@property (class, readonly) NCPPreviewOpenFailure *invalidDocumentId __attribute__((swift_name("invalidDocumentId")));
@property (class, readonly) NCPPreviewOpenFailure *invalidTitle __attribute__((swift_name("invalidTitle")));
@property (class, readonly) NCPPreviewOpenFailure *invalidPage __attribute__((swift_name("invalidPage")));
@property (class, readonly) NCPPreviewOpenFailure *inputCapacity __attribute__((swift_name("inputCapacity")));
@property (class, readonly) NSArray<NCPPreviewOpenFailure *> *entries __attribute__((swift_name("entries")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewReadFailure")))
@interface NCPPreviewReadFailure : NCPKotlinEnum<NCPPreviewReadFailure *>
+ (instancetype)alloc __attribute__((unavailable));


+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewReadFailure *characterCapacity __attribute__((swift_name("characterCapacity")));
@property (class, readonly) NCPPreviewReadFailure *invalidOrUnsupportedFormat __attribute__((swift_name("invalidOrUnsupportedFormat")));
@property (class, readonly) NCPPreviewReadFailure *unsupportedPreviewContent __attribute__((swift_name("unsupportedPreviewContent")));
@property (class, readonly) NSArray<NCPPreviewReadFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewRecoveryCodec")))
@interface NCPPreviewRecoveryCodec : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
@property (class, readonly, getter=shared) NCPPreviewRecoveryCodec *shared __attribute__((swift_name("shared")));








- (NCPPreviewRecoveryRead *)readText:(NSString *)text __attribute__((swift_name("read(text:)")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewRecoveryRead")))
@interface NCPPreviewRecoveryRead : NCPBase
@property (readonly) NCPPreviewReadFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPPagedPreviewSnapshot * _Nullable snapshot __attribute__((swift_name("snapshot")));
@end



__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSessionFailure")))
@interface NCPPreviewSessionFailure : NCPBase
@property (readonly) NCPDocumentRejection * _Nullable documentReason __attribute__((swift_name("documentReason")));
@property (readonly) NCPPreviewFailureKind *kind __attribute__((swift_name("kind")));
@end





























#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
