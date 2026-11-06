#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class NCPAdmissionRejection, NCPAdmissionResultAdmitted, NCPAdmissionResultExisting, NCPAdmissionResultRejected, NCPAffine2D, NCPAppleBrushJobExperiment, NCPAppleBrushReaderExperiment, NCPAppleBrushResultExperiment, NCPApplePreviewInput, NCPBoundsD, NCPBrushBlendMode, NCPBrushFillRule, NCPBrushGeometry, NCPBrushGeometryBudget, NCPBrushGeometryBuilder, NCPBrushGeometryRejection, NCPBrushGeometryResultReady, NCPBrushGeometryResultRejected, NCPBrushKind, NCPBrushPathPlan, NCPBrushPathPlanResultReady, NCPBrushPathPlanResultRejected, NCPBrushPathPlanner, NCPBrushPathPrecision, NCPBrushPathRejection, NCPBrushSpec, NCPCacheAdmissionStored<K, V>, NCPCanvasCommandAddStroke, NCPCanvasCommandDeleteStroke, NCPCanvasCommandTransformStroke, NCPCommandId, NCPCommitReceipt, NCPCommitResultAccepted, NCPCommitResultRejected, NCPCompletedInput, NCPCompletionUpdate, NCPDispatchResultAttemptNumbersExhausted, NCPDispatchResultBlocked, NCPDispatchResultBusy, NCPDispatchResultNoWork, NCPDispatchResultReady, NCPDispatchResultRetryRequired, NCPDisplacedEntry<K, V>, NCPDocumentAuthorityStatus, NCPDocumentBudget, NCPDocumentBudgetCompanion, NCPDocumentBudgetCreation, NCPDocumentBudgetFailure, NCPDocumentClosePhase, NCPDocumentCloseStatus, NCPDocumentCommandCanvas, NCPDocumentCommandDeletePage, NCPDocumentCommandInsertPage, NCPDocumentCommandMovePage, NCPDocumentCommandRedo, NCPDocumentCommandRename, NCPDocumentCommandSetPaper, NCPDocumentCommandUndo, NCPDocumentCommitAccepted, NCPDocumentCommitRejected, NCPDocumentReceipt, NCPDocumentRejection, NCPDocumentRoot, NCPDocumentRootCompanion, NCPDocumentSession, NCPDurability, NCPFrameToken, NCPGeometryCacheKey, NCPInputBatch, NCPInputRejection, NCPInputReplaySessionCompanion, NCPInputResultCancelled, NCPInputResultCompleted, NCPInputResultIgnoredNavigation, NCPInputResultPreview, NCPInputResultRejected, NCPInputSample, NCPKotlinArray<T>, NCPKotlinDoubleArray, NCPKotlinDoubleIterator, NCPKotlinEnum<E>, NCPKotlinEnumCompanion, NCPKotlinException, NCPKotlinFloatArray, NCPKotlinFloatIterator, NCPKotlinLongArray, NCPKotlinLongIterator, NCPKotlinPair<__covariant A, __covariant B>, NCPKotlinRuntimeException, NCPKotlinThrowable, NCPLayerId, NCPLeaseReturn, NCPObjectChange, NCPObjectId, NCPOwnedWorkStage, NCPOwnedWorkSummary, NCPOwnerExecutorStatus, NCPOwnerWorkTicket, NCPPageDescriptor, NCPPageId, NCPPagedPreviewCodec, NCPPagedPreviewHeader, NCPPagedPreviewNotebook, NCPPagedPreviewNotebookCompanion, NCPPagedPreviewSnapshot, NCPPagedPreviewSnapshotCompanion, NCPPaperKind, NCPPointD, NCPPointerPhase, NCPPointerTool, NCPPrepareResultPrepared, NCPPrepareResultRejected, NCPPreviewCatalogBudget, NCPPreviewDocumentRead, NCPPreviewDocumentReader, NCPPreviewEditCopyPage, NCPPreviewEditDeletePage, NCPPreviewEditInsertPage, NCPPreviewEditMovePage, NCPPreviewEditRedo, NCPPreviewEditRename, NCPPreviewEditResult, NCPPreviewEditSelectPage, NCPPreviewEditSelectPen, NCPPreviewEditSetPaper, NCPPreviewEditUndo, NCPPreviewFailureKind, NCPPreviewInputDisposition, NCPPreviewInputResult, NCPPreviewNotebookOpen, NCPPreviewOpenFailure, NCPPreviewPendingInk, NCPPreviewReadFailure, NCPPreviewRecoveryCodec, NCPPreviewRecoveryRead, NCPPreviewSaveAdmission, NCPPreviewSaveAttempt, NCPPreviewSaveState, NCPPreviewSessionFailure, NCPPreviewWriteAdmission, NCPPreviewWriteDisposition, NCPRecoveryOffer, NCPRecoveryPayloadBlocked, NCPRecoveryPayloadConfirmed, NCPRecoveryPayloadPending, NCPRejection, NCPReservationFailure, NCPReservationResultRejected, NCPReservationResultReserved, NCPResourceBorrowBorrowed<V>, NCPResourceBorrowClosed, NCPResourceBorrowLeaseLimit, NCPResourceBorrowMissing, NCPResourceCompletion, NCPResourceDrainStatus, NCPResourceHandle, NCPResourceLane, NCPResourceLease<V>, NCPResourcePublicationAlreadyOwned, NCPResourcePublicationResident, NCPResourcePublicationRetired, NCPResourcePublicationRetiredAfterClose, NCPResourcePublicationStaleReservation, NCPResourcePublicationUnsettledReservation, NCPResourceReleaseDispatchIdle, NCPResourceReleaseDispatchReady<V>, NCPResourceReleaseDispatchWrongLane, NCPResourceReleaseOutcome, NCPResourceReleaseTicket<V>, NCPResourceReservation, NCPResultDisposition, NCPResultDrain, NCPSceneApplicationApplied, NCPSceneApplicationRejected, NCPSceneId, NCPScenePatch, NCPSceneReducer, NCPSceneSnapshot, NCPSceneSnapshotCompanion, NCPSessionInputFailure, NCPSessionInputResultConsumed, NCPSessionInputResultRejected, NCPSessionOpenFailure, NCPSessionOpenResultOpened, NCPSessionOpenResultRejected, NCPSessionStop, NCPStrokeObject, NCPStrokeSamples, NCPStrokeSamplesCompanion, NCPStrokeSubmission, NCPStrokeTarget, NCPSubmissionAttempt, NCPSubmissionStateBlocked, NCPSubmissionStateConfirmed, NCPSubmissionStateInFlight, NCPSubmissionStateQueued, NCPSubmissionStateRetryRequired, NCPSubmissionSummary, NCPWorkAdmissionAccepted, NCPWorkAdmissionFailure, NCPWorkAdmissionRejected, NCPWorkResultNotExecuted, NCPWorkResultOutcomeUnknown, NCPWorkResultSucceeded<R>, NSData;

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
+ (void)initialize __attribute__((objc_requires_super));
@end

@interface NCPBase (NCPBaseCopying) <NSCopying>
@end

__attribute__((swift_name("KotlinMutableSet")))
@interface NCPMutableSet<ObjectType> : NSMutableSet<ObjectType>
@end

__attribute__((swift_name("KotlinMutableDictionary")))
@interface NCPMutableDictionary<KeyType, ObjectType> : NSMutableDictionary<KeyType, ObjectType>
@end

@interface NSError (NSErrorNCPKotlinException)
@property (readonly) id _Nullable kotlinException;
@end

__attribute__((swift_name("KotlinNumber")))
@interface NCPNumber : NSNumber
- (instancetype)initWithChar:(char)value __attribute__((unavailable));
- (instancetype)initWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
- (instancetype)initWithShort:(short)value __attribute__((unavailable));
- (instancetype)initWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
- (instancetype)initWithInt:(int)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
- (instancetype)initWithLong:(long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
- (instancetype)initWithLongLong:(long long)value __attribute__((unavailable));
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
- (instancetype)initWithFloat:(float)value __attribute__((unavailable));
- (instancetype)initWithDouble:(double)value __attribute__((unavailable));
- (instancetype)initWithBool:(BOOL)value __attribute__((unavailable));
- (instancetype)initWithInteger:(NSInteger)value __attribute__((unavailable));
- (instancetype)initWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
+ (instancetype)numberWithChar:(char)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedChar:(unsigned char)value __attribute__((unavailable));
+ (instancetype)numberWithShort:(short)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedShort:(unsigned short)value __attribute__((unavailable));
+ (instancetype)numberWithInt:(int)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInt:(unsigned int)value __attribute__((unavailable));
+ (instancetype)numberWithLong:(long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLong:(unsigned long)value __attribute__((unavailable));
+ (instancetype)numberWithLongLong:(long long)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value __attribute__((unavailable));
+ (instancetype)numberWithFloat:(float)value __attribute__((unavailable));
+ (instancetype)numberWithDouble:(double)value __attribute__((unavailable));
+ (instancetype)numberWithBool:(BOOL)value __attribute__((unavailable));
+ (instancetype)numberWithInteger:(NSInteger)value __attribute__((unavailable));
+ (instancetype)numberWithUnsignedInteger:(NSUInteger)value __attribute__((unavailable));
@end

__attribute__((swift_name("KotlinByte")))
@interface NCPByte : NCPNumber
- (instancetype)initWithChar:(char)value;
+ (instancetype)numberWithChar:(char)value;
@end

__attribute__((swift_name("KotlinUByte")))
@interface NCPUByte : NCPNumber
- (instancetype)initWithUnsignedChar:(unsigned char)value;
+ (instancetype)numberWithUnsignedChar:(unsigned char)value;
@end

__attribute__((swift_name("KotlinShort")))
@interface NCPShort : NCPNumber
- (instancetype)initWithShort:(short)value;
+ (instancetype)numberWithShort:(short)value;
@end

__attribute__((swift_name("KotlinUShort")))
@interface NCPUShort : NCPNumber
- (instancetype)initWithUnsignedShort:(unsigned short)value;
+ (instancetype)numberWithUnsignedShort:(unsigned short)value;
@end

__attribute__((swift_name("KotlinInt")))
@interface NCPInt : NCPNumber
- (instancetype)initWithInt:(int)value;
+ (instancetype)numberWithInt:(int)value;
@end

__attribute__((swift_name("KotlinUInt")))
@interface NCPUInt : NCPNumber
- (instancetype)initWithUnsignedInt:(unsigned int)value;
+ (instancetype)numberWithUnsignedInt:(unsigned int)value;
@end

__attribute__((swift_name("KotlinLong")))
@interface NCPLong : NCPNumber
- (instancetype)initWithLongLong:(long long)value;
+ (instancetype)numberWithLongLong:(long long)value;
@end

__attribute__((swift_name("KotlinULong")))
@interface NCPULong : NCPNumber
- (instancetype)initWithUnsignedLongLong:(unsigned long long)value;
+ (instancetype)numberWithUnsignedLongLong:(unsigned long long)value;
@end

__attribute__((swift_name("KotlinFloat")))
@interface NCPFloat : NCPNumber
- (instancetype)initWithFloat:(float)value;
+ (instancetype)numberWithFloat:(float)value;
@end

__attribute__((swift_name("KotlinDouble")))
@interface NCPDouble : NCPNumber
- (instancetype)initWithDouble:(double)value;
+ (instancetype)numberWithDouble:(double)value;
@end

__attribute__((swift_name("KotlinBoolean")))
@interface NCPBoolean : NCPNumber
- (instancetype)initWithBool:(BOOL)value;
+ (instancetype)numberWithBool:(BOOL)value;
@end

__attribute__((swift_name("KotlinComparable")))
@protocol NCPKotlinComparable
@required
- (int32_t)compareToOther:(id _Nullable)other __attribute__((swift_name("compareTo(other:)")));
@end

__attribute__((swift_name("KotlinEnum")))
@interface NCPKotlinEnum<E> : NCPBase <NCPKotlinComparable>
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPKotlinEnumCompanion *companion __attribute__((swift_name("companion")));
- (int32_t)compareToOther:(E)other __attribute__((swift_name("compareTo(other:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *name __attribute__((swift_name("name")));
@property (readonly) int32_t ordinal __attribute__((swift_name("ordinal")));
@end


/** Preview full-snapshot replacement only; not a command ledger or production durable authority.
 * Single owner; an admitted latest snapshot and every in-flight older snapshot retain their budgets.
 * Version order assumes one live canonical notebook owner per document, never concurrent forks.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSaveAdmission")))
@interface NCPPreviewSaveAdmission : NCPKotlinEnum<NCPPreviewSaveAdmission *>
+ (instancetype)alloc __attribute__((unavailable));

/** Preview full-snapshot replacement only; not a command ledger or production durable authority.
 * Single owner; an admitted latest snapshot and every in-flight older snapshot retain their budgets.
 * Version order assumes one live canonical notebook owner per document, never concurrent forks.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewSaveAdmission *accepted __attribute__((swift_name("accepted")));
@property (class, readonly) NCPPreviewSaveAdmission *staleVersion __attribute__((swift_name("staleVersion")));
@property (class, readonly) NCPPreviewSaveAdmission *versionConflict __attribute__((swift_name("versionConflict")));
@property (class, readonly) NCPPreviewSaveAdmission *documentCapacity __attribute__((swift_name("documentCapacity")));
@property (class, readonly) NCPPreviewSaveAdmission *byteCapacity __attribute__((swift_name("byteCapacity")));
+ (NCPKotlinArray<NCPPreviewSaveAdmission *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPreviewSaveAdmission *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSaveAttempt")))
@interface NCPPreviewSaveAttempt : NCPBase
@property (readonly) int64_t number __attribute__((swift_name("number")));
@property (readonly) id<NCPPreviewSaveSnapshot> snapshot __attribute__((swift_name("snapshot")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSaveLedger")))
@interface NCPPreviewSaveLedger : NCPBase
- (instancetype)initWithMaxDocuments:(int32_t)maxDocuments byteBudget:(int64_t)byteBudget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("init(maxDocuments:byteBudget:ownerCheck:)"))) __attribute__((objc_designated_initializer));

/** Owner reads the original executor's remaining/total declared bytes immediately before this call.
 * A later executor slot/stop refusal must returnUnstarted; no side effect has run at admission time.
 * Skip permanently oversized documents, never a same-document flight. A temporarily blocked
 * eligible document keeps its place so a stream of smaller saves cannot starve it.
 */
- (NCPPreviewWriteAdmission *)admitNextWriteAvailableWorkBytes:(int64_t)availableWorkBytes maximumWorkBytes:(int64_t)maximumWorkBytes backendAccepting:(BOOL)backendAccepting __attribute__((swift_name("admitNextWrite(availableWorkBytes:maximumWorkBytes:backendAccepting:)")));

/** Success means the platform completed its atomic write. Unknown/failure retains full content for explicit retry.
 * A newer admitted complete snapshot survives either result and remains eligible to write.
 */
- (BOOL)completeAttempt:(NCPPreviewSaveAttempt *)attempt success:(BOOL)success __attribute__((swift_name("complete(attempt:success:)")));
- (id<NCPPreviewSaveSnapshot> _Nullable)latestDocumentId:(NSString *)documentId __attribute__((swift_name("latest(documentId:)")));

/** Compatibility polling only. Hosts use the typed admission to distinguish capacity and exhaustion. */
- (NCPPreviewSaveAttempt * _Nullable)nextWrite __attribute__((swift_name("nextWrite()")));

/** Rejected snapshots remain with caller. A newer complete snapshot can supersede unsent/failed older content.
 * An in-flight older write is never replaced or freed before its matching completion.
 * Same-version admission retries the retained immutable content; it does not replace it with a fork.
 */
- (NCPPreviewSaveAdmission *)offerSnapshot:(id<NCPPreviewSaveSnapshot>)snapshot __attribute__((swift_name("offer(snapshot:)")));

/** Backend rejected before execution; return to pending without reporting a disk write failure. */
- (BOOL)returnUnstartedAttempt:(NCPPreviewSaveAttempt *)attempt __attribute__((swift_name("returnUnstarted(attempt:)")));
- (NSArray<NCPPreviewSaveState *> *)states __attribute__((swift_name("states()")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) int32_t documentCount __attribute__((swift_name("documentCount")));
@property (readonly) BOOL isDrained __attribute__((swift_name("isDrained")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSaveState")))
@interface NCPPreviewSaveState : NCPBase
- (instancetype)initWithSnapshot:(id<NCPPreviewSaveSnapshot>)snapshot inFlight:(BOOL)inFlight retryRequired:(BOOL)retryRequired __attribute__((swift_name("init(snapshot:inFlight:retryRequired:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewSaveState *)doCopySnapshot:(id<NCPPreviewSaveSnapshot>)snapshot inFlight:(BOOL)inFlight retryRequired:(BOOL)retryRequired __attribute__((swift_name("doCopy(snapshot:inFlight:retryRequired:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) BOOL inFlight __attribute__((swift_name("inFlight")));
@property (readonly) BOOL retryRequired __attribute__((swift_name("retryRequired")));
@property (readonly) id<NCPPreviewSaveSnapshot> snapshot __attribute__((swift_name("snapshot")));
@end


/** UI-owner presentation state. Never mutates document samples, revision or saved content. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewViewport")))
@interface NCPPreviewViewport : NCPBase

/** UI-owner presentation state. Never mutates document samples, revision or saved content. */
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));

/** UI-owner presentation state. Never mutates document samples, revision or saved content. */
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (void)configurePageWidth:(double)width height:(double)height __attribute__((swift_name("configurePage(width:height:)")));

/** Map the old finger centroid to the new one, anchored in scene coordinates. */
- (void)gestureOldX:(double)oldX oldY:(double)oldY newX:(double)newX newY:(double)newY ratio:(double)ratio __attribute__((swift_name("gesture(oldX:oldY:newX:newY:ratio:)")));
- (void)reset __attribute__((swift_name("reset()")));
- (void)resizeWidth:(double)width height:(double)height __attribute__((swift_name("resize(width:height:)")));
- (NCPPointD *)toSceneX:(double)x y:(double)y __attribute__((swift_name("toScene(x:y:)")));
- (NCPPointD *)toViewX:(double)x y:(double)y __attribute__((swift_name("toView(x:y:)")));
@property (readonly) double height __attribute__((swift_name("height")));
@property (readonly) double offsetX __attribute__((swift_name("offsetX")));
@property (readonly) double offsetY __attribute__((swift_name("offsetY")));
@property (readonly) double pageHeight __attribute__((swift_name("pageHeight")));
@property (readonly) double pageWidth __attribute__((swift_name("pageWidth")));
@property (readonly) double scale __attribute__((swift_name("scale")));
@property (readonly) double width __attribute__((swift_name("width")));
@property (readonly) double zoom __attribute__((swift_name("zoom")));
@end


/** Only READY transfers an attempt to the caller. Other results leave every snapshot in the ledger. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewWriteAdmission")))
@interface NCPPreviewWriteAdmission : NCPBase
@property (readonly) NCPPreviewSaveAttempt * _Nullable attempt __attribute__((swift_name("attempt")));
@property (readonly) NCPPreviewWriteDisposition *disposition __attribute__((swift_name("disposition")));
@property (readonly) NSString * _Nullable documentId __attribute__((swift_name("documentId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewWriteDisposition")))
@interface NCPPreviewWriteDisposition : NCPKotlinEnum<NCPPreviewWriteDisposition *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewWriteDisposition *ready __attribute__((swift_name("ready")));
@property (class, readonly) NCPPreviewWriteDisposition *idle __attribute__((swift_name("idle")));
@property (class, readonly) NCPPreviewWriteDisposition *backpressure __attribute__((swift_name("backpressure")));
@property (class, readonly) NCPPreviewWriteDisposition *workCapacity __attribute__((swift_name("workCapacity")));
@property (class, readonly) NCPPreviewWriteDisposition *invalidBudget __attribute__((swift_name("invalidBudget")));
@property (class, readonly) NCPPreviewWriteDisposition *identityExhausted __attribute__((swift_name("identityExhausted")));
@property (class, readonly) NCPPreviewWriteDisposition *backendUnavailable __attribute__((swift_name("backendUnavailable")));
+ (NCPKotlinArray<NCPPreviewWriteDisposition *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPreviewWriteDisposition *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AdmissionRejection")))
@interface NCPAdmissionRejection : NCPKotlinEnum<NCPAdmissionRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPAdmissionRejection *stopped __attribute__((swift_name("stopped")));
@property (class, readonly) NCPAdmissionRejection *wrongScene __attribute__((swift_name("wrongScene")));
@property (class, readonly) NCPAdmissionRejection *reusedId __attribute__((swift_name("reusedId")));
@property (class, readonly) NCPAdmissionRejection *entryCapacity __attribute__((swift_name("entryCapacity")));
@property (class, readonly) NCPAdmissionRejection *byteCapacity __attribute__((swift_name("byteCapacity")));
+ (NCPKotlinArray<NCPAdmissionRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPAdmissionRejection *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("AdmissionResult")))
@protocol NCPAdmissionResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AdmissionResultAdmitted")))
@interface NCPAdmissionResultAdmitted : NCPBase <NCPAdmissionResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)admitted __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPAdmissionResultAdmitted *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AdmissionResultExisting")))
@interface NCPAdmissionResultExisting : NCPBase <NCPAdmissionResult>
- (instancetype)initWithState:(id<NCPSubmissionState>)state __attribute__((swift_name("init(state:)"))) __attribute__((objc_designated_initializer));
- (NCPAdmissionResultExisting *)doCopyState:(id<NCPSubmissionState>)state __attribute__((swift_name("doCopy(state:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) id<NCPSubmissionState> state __attribute__((swift_name("state")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AdmissionResultRejected")))
@interface NCPAdmissionResultRejected : NCPBase <NCPAdmissionResult>
- (instancetype)initWithReason:(NCPAdmissionRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPAdmissionResultRejected *)doCopyReason:(NCPAdmissionRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPAdmissionRejection *reason __attribute__((swift_name("reason")));
@end


/** x'=a*x+c*y+tx, y'=b*x+d*y+ty; then() applies this transform first. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Affine2D")))
@interface NCPAffine2D : NCPBase
- (instancetype)initWithA:(double)a b:(double)b c:(double)c d:(double)d tx:(double)tx ty:(double)ty __attribute__((swift_name("init(a:b:c:d:tx:ty:)"))) __attribute__((objc_designated_initializer));
- (NCPAffine2D *)doCopyA:(double)a b:(double)b c:(double)c d:(double)d tx:(double)tx ty:(double)ty __attribute__((swift_name("doCopy(a:b:c:d:tx:ty:)")));

/** x'=a*x+c*y+tx, y'=b*x+d*y+ty; then() applies this transform first. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Same supported inverse as inverseOrNull, without allocating a derived matrix. Unfrozen. */
- (BOOL)hasInverse __attribute__((swift_name("hasInverse()")));

/** x'=a*x+c*y+tx, y'=b*x+d*y+ty; then() applies this transform first. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NCPAffine2D * _Nullable)inverseOrNull __attribute__((swift_name("inverseOrNull()")));
- (NCPPointD *)mapPoint:(NCPPointD *)point __attribute__((swift_name("map(point:)")));
- (NCPBoundsD *)mapBoundsBounds:(NCPBoundsD *)bounds __attribute__((swift_name("mapBounds(bounds:)")));
- (NCPAffine2D *)thenNext:(NCPAffine2D *)next __attribute__((swift_name("then(next:)")));

/** x'=a*x+c*y+tx, y'=b*x+d*y+ty; then() applies this transform first. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) double a __attribute__((swift_name("a")));
@property (readonly) double b __attribute__((swift_name("b")));
@property (readonly) double c __attribute__((swift_name("c")));
@property (readonly) double d __attribute__((swift_name("d")));
@property (readonly) double tx __attribute__((swift_name("tx")));
@property (readonly) double ty __attribute__((swift_name("ty")));
@end


/** Unfrozen ObjC export shim; internal renderer/types remain internal. No scheduling here. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AppleBrushJobExperiment")))
@interface NCPAppleBrushJobExperiment : NCPBase
- (NCPAppleBrushResultExperiment *)executeCancelled:(NCPBoolean *(^)(void))cancelled __attribute__((swift_name("execute(cancelled:)")));
- (NCPAppleBrushResultExperiment *)unknownMessage:(NSString *)message __attribute__((swift_name("unknown(message:)")));
@property (readonly) NCPFrameToken *frame __attribute__((swift_name("frame")));
@property (readonly) int32_t height __attribute__((swift_name("height")));
@property (readonly) int64_t nativeAllowance __attribute__((swift_name("nativeAllowance")));
@property (readonly) int64_t peakBytes __attribute__((swift_name("peakBytes")));
@property (readonly) int32_t width __attribute__((swift_name("width")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AppleBrushOwnerExperiment")))
@interface NCPAppleBrushOwnerExperiment : NCPBase
- (instancetype)initWithFrame:(NCPFrameToken *)frame layer:(NCPLayerId *)layer residentBudget:(int64_t)residentBudget totalBudget:(int64_t)totalBudget maxJobs:(int32_t)maxJobs checkOwner:(void (^)(void))checkOwner __attribute__((swift_name("init(frame:layer:residentBudget:totalBudget:maxJobs:checkOwner:)"))) __attribute__((objc_designated_initializer));
- (NSString *)acknowledgeJob:(NCPAppleBrushJobExperiment *)job __attribute__((swift_name("acknowledge(job:)")));
- (NCPAppleBrushJobExperiment * _Nullable)admitStroke:(NCPStrokeObject *)stroke frame:(NCPFrameToken *)frame originX:(double)originX originY:(double)originY width:(int32_t)width height:(int32_t)height pixelSize:(double)pixelSize workingBytes:(int64_t)workingBytes nativeAllowance:(int64_t)nativeAllowance __attribute__((swift_name("admit(stroke:frame:originX:originY:width:height:pixelSize:workingBytes:nativeAllowance:)")));
- (NCPAppleBrushJobExperiment * _Nullable)admitPageScene:(NCPSceneSnapshot *)scene frame:(NCPFrameToken *)frame layer:(NCPLayerId *)layer originX:(double)originX originY:(double)originY width:(int32_t)width height:(int32_t)height pixelSize:(double)pixelSize workingBytes:(int64_t)workingBytes nativeAllowance:(int64_t)nativeAllowance maxEdgeTests:(int64_t)maxEdgeTests __attribute__((swift_name("admitPage(scene:frame:layer:originX:originY:width:height:pixelSize:workingBytes:nativeAllowance:maxEdgeTests:)")));
- (NCPAppleBrushReaderExperiment * _Nullable)borrowNativeJob:(NCPAppleBrushJobExperiment *)job __attribute__((swift_name("borrowNative(job:)")));
- (BOOL)canPublishReader:(NCPAppleBrushReaderExperiment *)reader __attribute__((swift_name("canPublish(reader:)")));
- (BOOL)cancelJob:(NCPAppleBrushJobExperiment *)job __attribute__((swift_name("cancel(job:)")));
- (void)close __attribute__((swift_name("close()")));
- (BOOL)expectFrame:(NCPFrameToken *)frame layer:(NCPLayerId *)layer __attribute__((swift_name("expect(frame:layer:)")));
- (BOOL)receiveJob:(NCPAppleBrushJobExperiment *)job result:(NCPAppleBrushResultExperiment *)result __attribute__((swift_name("receive(job:result:)")));
- (BOOL)reconcileNoOutputJob:(NCPAppleBrushJobExperiment *)job __attribute__((swift_name("reconcileNoOutput(job:)")));
- (BOOL)reconcileReleasedReader:(NCPAppleBrushReaderExperiment *)reader __attribute__((swift_name("reconcileReleased(reader:)")));
- (BOOL)rejectJob:(NCPAppleBrushJobExperiment *)job __attribute__((swift_name("reject(job:)")));
- (BOOL)releaseReader:(NCPAppleBrushReaderExperiment *)reader unknown:(BOOL)unknown __attribute__((swift_name("release(reader:unknown:)")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) BOOL isDrained __attribute__((swift_name("isDrained")));
@property (readonly) int64_t retiredBytes __attribute__((swift_name("retiredBytes")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AppleBrushReaderExperiment")))
@interface NCPAppleBrushReaderExperiment : NCPBase
- (int32_t)byteAtOffset:(int32_t)offset __attribute__((swift_name("byteAt(offset:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("AppleBrushResultExperiment")))
@interface NCPAppleBrushResultExperiment : NCPBase
@property (readonly) BOOL outcomeUnknown __attribute__((swift_name("outcomeUnknown")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BoundsD")))
@interface NCPBoundsD : NCPBase
- (instancetype)initWithLeft:(double)left top:(double)top right:(double)right bottom:(double)bottom __attribute__((swift_name("init(left:top:right:bottom:)"))) __attribute__((objc_designated_initializer));
- (NCPBoundsD *)doCopyLeft:(double)left top:(double)top right:(double)right bottom:(double)bottom __attribute__((swift_name("doCopy(left:top:right:bottom:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
- (NCPBoundsD *)unionOther:(NCPBoundsD *)other __attribute__((swift_name("union(other:)")));
@property (readonly) double bottom __attribute__((swift_name("bottom")));
@property (readonly) double left __attribute__((swift_name("left")));
@property (readonly) double right __attribute__((swift_name("right")));
@property (readonly) double top __attribute__((swift_name("top")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushBlendMode")))
@interface NCPBrushBlendMode : NCPKotlinEnum<NCPBrushBlendMode *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushBlendMode *sourceOver __attribute__((swift_name("sourceOver")));
+ (NCPKotlinArray<NCPBrushBlendMode *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushBlendMode *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushFillRule")))
@interface NCPBrushFillRule : NCPKotlinEnum<NCPBrushFillRule *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushFillRule *nonZero __attribute__((swift_name("nonZero")));
+ (NCPKotlinArray<NCPBrushFillRule *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushFillRule *> *entries __attribute__((swift_name("entries")));
@end


/** Numeric contours in sample-local space. Fill ALL contours once, never each contour separately. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometry")))
@interface NCPBrushGeometry : NCPBase
- (int32_t)contourEndContour:(int32_t)contour __attribute__((swift_name("contourEnd(contour:)")));
- (int32_t)contourStartContour:(int32_t)contour __attribute__((swift_name("contourStart(contour:)")));
- (double)xVertex:(int32_t)vertex __attribute__((swift_name("x(vertex:)")));
- (double)yVertex:(int32_t)vertex __attribute__((swift_name("y(vertex:)")));
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) NCPBrushBlendMode *blendMode __attribute__((swift_name("blendMode")));
@property (readonly) NCPBoundsD *bounds __attribute__((swift_name("bounds")));
@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@property (readonly) int32_t contourCount __attribute__((swift_name("contourCount")));
@property (readonly) NCPBrushFillRule *fillRule __attribute__((swift_name("fillRule")));
@property (readonly) int64_t logicalByteCharge __attribute__((swift_name("logicalByteCharge")));
@property (readonly) NCPPointD *origin __attribute__((swift_name("origin")));
@property (readonly) int32_t sourcePointCount __attribute__((swift_name("sourcePointCount")));
@property (readonly) int32_t vertexCount __attribute__((swift_name("vertexCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometryBudget")))
@interface NCPBrushGeometryBudget : NCPBase
- (instancetype)initWithMaxSamples:(int32_t)maxSamples maxVertices:(int32_t)maxVertices logicalBytes:(int64_t)logicalBytes __attribute__((swift_name("init(maxSamples:maxVertices:logicalBytes:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushGeometryBudget *)doCopyMaxSamples:(int32_t)maxSamples maxVertices:(int32_t)maxVertices logicalBytes:(int64_t)logicalBytes __attribute__((swift_name("doCopy(maxSamples:maxVertices:logicalBytes:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t logicalBytes __attribute__((swift_name("logicalBytes")));
@property (readonly) int32_t maxSamples __attribute__((swift_name("maxSamples")));
@property (readonly) int32_t maxVertices __attribute__((swift_name("maxVertices")));
@end


/** Versioned reference geometry only. No platform paths, smoothing, persistence or publication. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometryBuilder")))
@interface NCPBrushGeometryBuilder : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/** Versioned reference geometry only. No platform paths, smoothing, persistence or publication. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)brushGeometryBuilder __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPBrushGeometryBuilder *shared __attribute__((swift_name("shared")));
- (id<NCPBrushGeometryResult>)buildSamples:(NCPStrokeSamples *)samples brush:(NCPBrushSpec *)brush budget:(NCPBrushGeometryBudget *)budget __attribute__((swift_name("build(samples:brush:budget:)")));
@property (readonly) int32_t ALGORITHM_VERSION __attribute__((swift_name("ALGORITHM_VERSION")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometryRejection")))
@interface NCPBrushGeometryRejection : NCPKotlinEnum<NCPBrushGeometryRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushGeometryRejection *unsupportedAlgorithm __attribute__((swift_name("unsupportedAlgorithm")));
@property (class, readonly) NCPBrushGeometryRejection *sampleCapacity __attribute__((swift_name("sampleCapacity")));
@property (class, readonly) NCPBrushGeometryRejection *vertexCapacity __attribute__((swift_name("vertexCapacity")));
@property (class, readonly) NCPBrushGeometryRejection *byteCapacity __attribute__((swift_name("byteCapacity")));
@property (class, readonly) NCPBrushGeometryRejection *numericRange __attribute__((swift_name("numericRange")));
+ (NCPKotlinArray<NCPBrushGeometryRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushGeometryRejection *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("BrushGeometryResult")))
@protocol NCPBrushGeometryResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometryResultReady")))
@interface NCPBrushGeometryResultReady : NCPBase <NCPBrushGeometryResult>
- (instancetype)initWithGeometry:(NCPBrushGeometry *)geometry __attribute__((swift_name("init(geometry:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushGeometryResultReady *)doCopyGeometry:(NCPBrushGeometry *)geometry __attribute__((swift_name("doCopy(geometry:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushGeometry *geometry __attribute__((swift_name("geometry")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushGeometryResultRejected")))
@interface NCPBrushGeometryResultRejected : NCPBase <NCPBrushGeometryResult>
- (instancetype)initWithReason:(NCPBrushGeometryRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushGeometryResultRejected *)doCopyReason:(NCPBrushGeometryRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushGeometryRejection *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushKind")))
@interface NCPBrushKind : NCPKotlinEnum<NCPBrushKind *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushKind *monoline __attribute__((swift_name("monoline")));
@property (class, readonly) NCPBrushKind *pressure __attribute__((swift_name("pressure")));
@property (class, readonly) NCPBrushKind *highlighter __attribute__((swift_name("highlighter")));
+ (NCPKotlinArray<NCPBrushKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushKind *> *entries __attribute__((swift_name("entries")));
@end


/** Immutable preflighted target-pixel path; retains the input geometry, not a second coordinate array. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathPlan")))
@interface NCPBrushPathPlan : NCPBase
- (int32_t)contourEndContour:(int32_t)contour __attribute__((swift_name("contourEnd(contour:)")));
- (int32_t)contourStartContour:(int32_t)contour __attribute__((swift_name("contourStart(contour:)")));
- (double)xVertex:(int32_t)vertex __attribute__((swift_name("x(vertex:)")));
- (double)yVertex:(int32_t)vertex __attribute__((swift_name("y(vertex:)")));
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) NCPBrushBlendMode *blendMode __attribute__((swift_name("blendMode")));
@property (readonly) int32_t contourCount __attribute__((swift_name("contourCount")));
@property (readonly) NCPBrushFillRule *fillRule __attribute__((swift_name("fillRule")));
@property (readonly) NCPBrushPathPrecision *precision __attribute__((swift_name("precision")));
@property (readonly) int32_t vertexCount __attribute__((swift_name("vertexCount")));
@end

__attribute__((swift_name("BrushPathPlanResult")))
@protocol NCPBrushPathPlanResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathPlanResultReady")))
@interface NCPBrushPathPlanResultReady : NCPBase <NCPBrushPathPlanResult>
- (instancetype)initWithPlan:(NCPBrushPathPlan *)plan __attribute__((swift_name("init(plan:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushPathPlanResultReady *)doCopyPlan:(NCPBrushPathPlan *)plan __attribute__((swift_name("doCopy(plan:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushPathPlan *plan __attribute__((swift_name("plan")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathPlanResultRejected")))
@interface NCPBrushPathPlanResultRejected : NCPBase <NCPBrushPathPlanResult>
- (instancetype)initWithReason:(NCPBrushPathRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushPathPlanResultRejected *)doCopyReason:(NCPBrushPathRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushPathRejection *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathPlanner")))
@interface NCPBrushPathPlanner : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)brushPathPlanner __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPBrushPathPlanner *shared __attribute__((swift_name("shared")));

/** Compose object+viewport into sceneToTarget before calling. Native paths allocate only after Ready. */
- (id<NCPBrushPathPlanResult>)prepareGeometry:(NCPBrushGeometry *)geometry sceneToTarget:(NCPAffine2D *)sceneToTarget precision:(NCPBrushPathPrecision *)precision maxVertices:(int32_t)maxVertices __attribute__((swift_name("prepare(geometry:sceneToTarget:precision:maxVertices:)")));
@property (readonly) double FLOAT32_MAX_ERROR __attribute__((swift_name("FLOAT32_MAX_ERROR")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathPrecision")))
@interface NCPBrushPathPrecision : NCPKotlinEnum<NCPBrushPathPrecision *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushPathPrecision *float32 __attribute__((swift_name("float32")));
@property (class, readonly) NCPBrushPathPrecision *float64 __attribute__((swift_name("float64")));
+ (NCPKotlinArray<NCPBrushPathPrecision *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushPathPrecision *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushPathRejection")))
@interface NCPBrushPathRejection : NCPKotlinEnum<NCPBrushPathRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPBrushPathRejection *vertexCapacity __attribute__((swift_name("vertexCapacity")));
@property (class, readonly) NCPBrushPathRejection *invalidTransform __attribute__((swift_name("invalidTransform")));
@property (class, readonly) NCPBrushPathRejection *nonFinite __attribute__((swift_name("nonFinite")));
@property (class, readonly) NCPBrushPathRejection *precisionLoss __attribute__((swift_name("precisionLoss")));
@property (class, readonly) NCPBrushPathRejection *degenerateContour __attribute__((swift_name("degenerateContour")));
+ (NCPKotlinArray<NCPBrushPathRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPBrushPathRejection *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushSpec")))
@interface NCPBrushSpec : NCPBase
- (instancetype)initWithKind:(NCPBrushKind *)kind algorithmVersion:(int32_t)algorithmVersion width:(double)width argb:(int32_t)argb __attribute__((swift_name("init(kind:algorithmVersion:width:argb:)"))) __attribute__((objc_designated_initializer));
- (NCPBrushSpec *)doCopyKind:(NCPBrushKind *)kind algorithmVersion:(int32_t)algorithmVersion width:(double)width argb:(int32_t)argb __attribute__((swift_name("doCopy(kind:algorithmVersion:width:argb:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t algorithmVersion __attribute__((swift_name("algorithmVersion")));
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) NCPBrushKind *kind __attribute__((swift_name("kind")));
@property (readonly) double width __attribute__((swift_name("width")));
@end

__attribute__((swift_name("CacheAdmission")))
@protocol NCPCacheAdmission
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CacheAdmissionInvalidWeight")))
@interface NCPCacheAdmissionInvalidWeight<K, V> : NCPBase <NCPCacheAdmission>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CacheAdmissionStored")))
@interface NCPCacheAdmissionStored<K, V> : NCPBase <NCPCacheAdmission>
- (instancetype)initWithDisplaced:(NSArray<NCPDisplacedEntry<K, V> *> *)displaced __attribute__((swift_name("init(displaced:)"))) __attribute__((objc_designated_initializer));
- (NCPCacheAdmissionStored<K, V> *)doCopyDisplaced:(NSArray<NCPDisplacedEntry<K, V> *> *)displaced __attribute__((swift_name("doCopy(displaced:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSArray<NCPDisplacedEntry<K, V> *> *displaced __attribute__((swift_name("displaced")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CacheAdmissionTooLarge")))
@interface NCPCacheAdmissionTooLarge<K, V> : NCPBase <NCPCacheAdmission>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
@end

__attribute__((swift_name("CanvasCommand")))
@protocol NCPCanvasCommand
@required
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CanvasCommandAddStroke")))
@interface NCPCanvasCommandAddStroke : NCPBase <NCPCanvasCommand>
- (instancetype)initWithId:(NCPCommandId *)id baseRevision:(int64_t)baseRevision target:(NCPStrokeTarget *)target objectId:(NCPObjectId *)objectId samples:(NCPStrokeSamples *)samples __attribute__((swift_name("init(id:baseRevision:target:objectId:samples:)"))) __attribute__((objc_designated_initializer));
- (NCPCanvasCommandAddStroke *)doCopyId:(NCPCommandId *)id baseRevision:(int64_t)baseRevision target:(NCPStrokeTarget *)target objectId:(NCPObjectId *)objectId samples:(NCPStrokeSamples *)samples __attribute__((swift_name("doCopy(id:baseRevision:target:objectId:samples:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPObjectId *objectId __attribute__((swift_name("objectId")));
@property (readonly) NCPStrokeSamples *samples __attribute__((swift_name("samples")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@property (readonly) NCPStrokeTarget *target __attribute__((swift_name("target")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CanvasCommandDeleteStroke")))
@interface NCPCanvasCommandDeleteStroke : NCPBase <NCPCanvasCommand>
- (instancetype)initWithId:(NCPCommandId *)id sceneId:(NCPSceneId *)sceneId baseRevision:(int64_t)baseRevision objectId:(NCPObjectId *)objectId expectedObjectRevision:(int64_t)expectedObjectRevision __attribute__((swift_name("init(id:sceneId:baseRevision:objectId:expectedObjectRevision:)"))) __attribute__((objc_designated_initializer));
- (NCPCanvasCommandDeleteStroke *)doCopyId:(NCPCommandId *)id sceneId:(NCPSceneId *)sceneId baseRevision:(int64_t)baseRevision objectId:(NCPObjectId *)objectId expectedObjectRevision:(int64_t)expectedObjectRevision __attribute__((swift_name("doCopy(id:sceneId:baseRevision:objectId:expectedObjectRevision:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) int64_t expectedObjectRevision __attribute__((swift_name("expectedObjectRevision")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPObjectId *objectId __attribute__((swift_name("objectId")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CanvasCommandTransformStroke")))
@interface NCPCanvasCommandTransformStroke : NCPBase <NCPCanvasCommand>
- (instancetype)initWithId:(NCPCommandId *)id sceneId:(NCPSceneId *)sceneId baseRevision:(int64_t)baseRevision objectId:(NCPObjectId *)objectId expectedObjectRevision:(int64_t)expectedObjectRevision transform:(NCPAffine2D *)transform __attribute__((swift_name("init(id:sceneId:baseRevision:objectId:expectedObjectRevision:transform:)"))) __attribute__((objc_designated_initializer));
- (NCPCanvasCommandTransformStroke *)doCopyId:(NCPCommandId *)id sceneId:(NCPSceneId *)sceneId baseRevision:(int64_t)baseRevision objectId:(NCPObjectId *)objectId expectedObjectRevision:(int64_t)expectedObjectRevision transform:(NCPAffine2D *)transform __attribute__((swift_name("doCopy(id:sceneId:baseRevision:objectId:expectedObjectRevision:transform:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) int64_t expectedObjectRevision __attribute__((swift_name("expectedObjectRevision")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPObjectId *objectId __attribute__((swift_name("objectId")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@property (readonly) NCPAffine2D *transform __attribute__((swift_name("transform")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CommandId")))
@interface NCPCommandId : NCPBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPCommandId *)doCopyValue:(NSString *)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CommitReceipt")))
@interface NCPCommitReceipt : NCPBase
- (instancetype)initWithCommandId:(NCPCommandId *)commandId sceneRevision:(int64_t)sceneRevision durability:(NCPDurability *)durability __attribute__((swift_name("init(commandId:sceneRevision:durability:)"))) __attribute__((objc_designated_initializer));
- (NCPCommitReceipt *)doCopyCommandId:(NCPCommandId *)commandId sceneRevision:(int64_t)sceneRevision durability:(NCPDurability *)durability __attribute__((swift_name("doCopy(commandId:sceneRevision:durability:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommandId *commandId __attribute__((swift_name("commandId")));
@property (readonly) NCPDurability *durability __attribute__((swift_name("durability")));
@property (readonly) int64_t sceneRevision __attribute__((swift_name("sceneRevision")));
@end

__attribute__((swift_name("CommitResult")))
@protocol NCPCommitResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CommitResultAccepted")))
@interface NCPCommitResultAccepted : NCPBase <NCPCommitResult>
- (instancetype)initWithReceipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("init(receipt:)"))) __attribute__((objc_designated_initializer));
- (NCPCommitResultAccepted *)doCopyReceipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("doCopy(receipt:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommitReceipt *receipt __attribute__((swift_name("receipt")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CommitResultRejected")))
@interface NCPCommitResultRejected : NCPBase <NCPCommitResult>
- (instancetype)initWithReason:(NCPRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPCommitResultRejected *)doCopyReason:(NCPRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CompletedInput")))
@interface NCPCompletedInput : NCPBase
- (instancetype)initWithTarget:(NCPStrokeTarget *)target samples:(NCPStrokeSamples *)samples __attribute__((swift_name("init(target:samples:)"))) __attribute__((objc_designated_initializer));

/** Only the document authority selects the current base revision at submission. */
- (NCPCanvasCommandAddStroke *)commandId:(NCPCommandId *)id objectId:(NCPObjectId *)objectId baseRevision:(int64_t)baseRevision __attribute__((swift_name("command(id:objectId:baseRevision:)")));
- (NCPCompletedInput *)doCopyTarget:(NCPStrokeTarget *)target samples:(NCPStrokeSamples *)samples __attribute__((swift_name("doCopy(target:samples:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPStrokeSamples *samples __attribute__((swift_name("samples")));
@property (readonly) NCPStrokeTarget *target __attribute__((swift_name("target")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("CompletionUpdate")))
@interface NCPCompletionUpdate : NCPKotlinEnum<NCPCompletionUpdate *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPCompletionUpdate *updated __attribute__((swift_name("updated")));
@property (class, readonly) NCPCompletionUpdate *staleAttempt __attribute__((swift_name("staleAttempt")));
@property (class, readonly) NCPCompletionUpdate *invalidReceipt __attribute__((swift_name("invalidReceipt")));
+ (NCPKotlinArray<NCPCompletionUpdate *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPCompletionUpdate *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("DispatchResult")))
@protocol NCPDispatchResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultAttemptNumbersExhausted")))
@interface NCPDispatchResultAttemptNumbersExhausted : NCPBase <NCPDispatchResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)attemptNumbersExhausted __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPDispatchResultAttemptNumbersExhausted *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultBlocked")))
@interface NCPDispatchResultBlocked : NCPBase <NCPDispatchResult>
- (instancetype)initWithId:(NCPCommandId *)id reason:(NCPRejection *)reason __attribute__((swift_name("init(id:reason:)"))) __attribute__((objc_designated_initializer));
- (NCPDispatchResultBlocked *)doCopyId:(NCPCommandId *)id reason:(NCPRejection *)reason __attribute__((swift_name("doCopy(id:reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultBusy")))
@interface NCPDispatchResultBusy : NCPBase <NCPDispatchResult>
- (instancetype)initWithId:(NCPCommandId *)id __attribute__((swift_name("init(id:)"))) __attribute__((objc_designated_initializer));
- (NCPDispatchResultBusy *)doCopyId:(NCPCommandId *)id __attribute__((swift_name("doCopy(id:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultNoWork")))
@interface NCPDispatchResultNoWork : NCPBase <NCPDispatchResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)noWork __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPDispatchResultNoWork *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultReady")))
@interface NCPDispatchResultReady : NCPBase <NCPDispatchResult>
- (instancetype)initWithAttempt:(NCPSubmissionAttempt *)attempt __attribute__((swift_name("init(attempt:)"))) __attribute__((objc_designated_initializer));
- (NCPDispatchResultReady *)doCopyAttempt:(NCPSubmissionAttempt *)attempt __attribute__((swift_name("doCopy(attempt:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPSubmissionAttempt *attempt __attribute__((swift_name("attempt")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DispatchResultRetryRequired")))
@interface NCPDispatchResultRetryRequired : NCPBase <NCPDispatchResult>
- (instancetype)initWithId:(NCPCommandId *)id __attribute__((swift_name("init(id:)"))) __attribute__((objc_designated_initializer));
- (NCPDispatchResultRetryRequired *)doCopyId:(NCPCommandId *)id __attribute__((swift_name("doCopy(id:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DisplacedEntry")))
@interface NCPDisplacedEntry<K, V> : NCPBase
- (instancetype)initWithKey:(K _Nullable)key value:(V _Nullable)value __attribute__((swift_name("init(key:value:)"))) __attribute__((objc_designated_initializer));
- (NCPDisplacedEntry<K, V> *)doCopyKey:(K _Nullable)key value:(V _Nullable)value __attribute__((swift_name("doCopy(key:value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) K _Nullable key __attribute__((swift_name("key")));
@property (readonly) V _Nullable value __attribute__((swift_name("value")));
@end


/** D3 reference closure, not a thread-safe executor, saved-to-disk receipt or platform GPU backend.
 * Owns all sessions, one bounded submission queue and one resource pool until explicit obligations drain.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCloseCoordinator")))
@interface NCPDocumentCloseCoordinator<K, V> : NCPBase
- (instancetype)initWithSceneId:(NCPSceneId *)sceneId maxSubmissions:(int32_t)maxSubmissions submissionBudget:(int64_t)submissionBudget residentBudget:(int64_t)residentBudget resourceBudget:(int64_t)resourceBudget maxSessions:(int32_t)maxSessions inputBudget:(int64_t)inputBudget maxResources:(int32_t)maxResources maxLeases:(int32_t)maxLeases releaseLane:(NCPResourceLane *)releaseLane ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("init(sceneId:maxSubmissions:submissionBudget:residentBudget:resourceBudget:maxSessions:inputBudget:maxResources:maxLeases:releaseLane:ownerCheck:)"))) __attribute__((objc_designated_initializer));

/** Explicit receiver acknowledgment transfers responsibility; an offer alone never frees charge. */
- (BOOL)acknowledgeOffer:(NCPRecoveryOffer *)offer __attribute__((swift_name("acknowledge(offer:)")));
- (NSArray<NCPSessionStop *> *)beginClose __attribute__((swift_name("beginClose()")));
- (id<NCPResourceBorrow>)borrowResourceKey:(K)key __attribute__((swift_name("borrowResource(key:)")));
- (BOOL)canPublishSession:(NCPDocumentSession *)session frame:(NCPFrameToken *)frame __attribute__((swift_name("canPublish(session:frame:)")));
- (BOOL)cancelUnallocatedReservation:(NCPResourceReservation *)reservation __attribute__((swift_name("cancelUnallocated(reservation:)")));

/** Only unfinished real input is cancelled, with its sample count reported to the host. */
- (NCPSessionStop * _Nullable)closeSessionSession:(NCPDocumentSession *)session __attribute__((swift_name("closeSession(session:)")));
- (NCPCompletionUpdate *)completeAttempt:(NCPSubmissionAttempt *)attempt result:(id<NCPCommitResult>)result __attribute__((swift_name("complete(attempt:result:)")));
- (NCPResourceCompletion *)completeReleaseTicket:(NCPResourceReleaseTicket<V> *)ticket outcome:(NCPResourceReleaseOutcome *)outcome __attribute__((swift_name("completeRelease(ticket:outcome:)")));
- (id<NCPSessionInputResult>)consumeSession:(NCPDocumentSession *)session batch:(NCPInputBatch *)batch targetAtDown:(NCPStrokeTarget *)targetAtDown __attribute__((swift_name("consume(session:batch:targetAtDown:)")));
- (id<NCPDispatchResult>)dispatchScene:(NCPSceneSnapshot *)scene __attribute__((swift_name("dispatch(scene:)")));
- (BOOL)expectFrameSession:(NCPDocumentSession *)session frame:(NCPFrameToken *)frame __attribute__((swift_name("expectFrame(session:frame:)")));
- (id<NCPResourceReleaseDispatch>)nextReleaseLane:(NCPResourceLane *)lane __attribute__((swift_name("nextRelease(lane:)")));

/** Host also calls this after external authority changes; snapshots never replace the authority. */
- (BOOL)observeSceneScene:(NCPSceneSnapshot *)scene __attribute__((swift_name("observeScene(scene:)")));
- (NCPRecoveryOffer * _Nullable)offerPendingSession:(NCPDocumentSession *)session __attribute__((swift_name("offerPending(session:)")));

/** In-flight/unknown outcomes are deliberately not transferable as definitive failures. */
- (NCPRecoveryOffer * _Nullable)offerSubmissionId:(NCPCommandId *)id __attribute__((swift_name("offerSubmission(id:)")));
- (id<NCPSessionOpenResult>)openSessionInitialFrame:(NCPFrameToken *)initialFrame maxRealSamples:(int32_t)maxRealSamples __attribute__((swift_name("openSession(initialFrame:maxRealSamples:)")));
- (NCPCompletedInput * _Nullable)pendingSession:(NCPDocumentSession *)session __attribute__((swift_name("pending(session:)")));
- (id<NCPResourcePublication>)publishResourceReservation:(NCPResourceReservation *)reservation key:(K)key value:(V)value __attribute__((swift_name("publishResource(reservation:key:value:)")));
- (id<NCPReservationResult>)reserveResourceBytes:(int64_t)bytes __attribute__((swift_name("reserveResource(bytes:)")));
- (NCPResourceCompletion *)resolveUnknownHandle:(NCPResourceHandle *)handle outcome:(NCPResourceReleaseOutcome *)outcome __attribute__((swift_name("resolveUnknown(handle:outcome:)")));
- (BOOL)retryId:(NCPCommandId *)id __attribute__((swift_name("retry(id:)")));
- (NCPLeaseReturn *)returnLeaseLease:(NCPResourceLease<V> *)lease __attribute__((swift_name("returnLease(lease:)")));
- (NCPDocumentCloseStatus *)status __attribute__((swift_name("status()")));

/** First assignment is stable across capacity rejection; a later call may not replace IDs. */
- (id<NCPAdmissionResult>)submitPendingSession:(NCPDocumentSession *)session id:(NCPCommandId *)id objectId:(NCPObjectId *)objectId __attribute__((swift_name("submitPending(session:id:objectId:)")));
- (NCPCompletionUpdate *)transportFailedAttempt:(NCPSubmissionAttempt *)attempt detail:(NSString *)detail __attribute__((swift_name("transportFailed(attempt:detail:)")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentClosePhase")))
@interface NCPDocumentClosePhase : NCPKotlinEnum<NCPDocumentClosePhase *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPDocumentClosePhase *open __attribute__((swift_name("open")));
@property (class, readonly) NCPDocumentClosePhase *closing __attribute__((swift_name("closing")));
@property (class, readonly) NCPDocumentClosePhase *closed __attribute__((swift_name("closed")));
+ (NCPKotlinArray<NCPDocumentClosePhase *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPDocumentClosePhase *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCloseStatus")))
@interface NCPDocumentCloseStatus : NCPBase
- (instancetype)initWithPhase:(NCPDocumentClosePhase *)phase pendingSessions:(int32_t)pendingSessions inputReservedBytes:(int64_t)inputReservedBytes submissions:(NSArray<NCPSubmissionSummary *> *)submissions submissionChargedBytes:(int64_t)submissionChargedBytes resources:(NCPResourceDrainStatus *)resources observedSceneRevision:(int64_t)observedSceneRevision __attribute__((swift_name("init(phase:pendingSessions:inputReservedBytes:submissions:submissionChargedBytes:resources:observedSceneRevision:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCloseStatus *)doCopyPhase:(NCPDocumentClosePhase *)phase pendingSessions:(int32_t)pendingSessions inputReservedBytes:(int64_t)inputReservedBytes submissions:(NSArray<NCPSubmissionSummary *> *)submissions submissionChargedBytes:(int64_t)submissionChargedBytes resources:(NCPResourceDrainStatus *)resources observedSceneRevision:(int64_t)observedSceneRevision __attribute__((swift_name("doCopy(phase:pendingSessions:inputReservedBytes:submissions:submissionChargedBytes:resources:observedSceneRevision:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t inputReservedBytes __attribute__((swift_name("inputReservedBytes")));
@property (readonly) int64_t observedSceneRevision __attribute__((swift_name("observedSceneRevision")));
@property (readonly) int32_t pendingSessions __attribute__((swift_name("pendingSessions")));
@property (readonly) NCPDocumentClosePhase *phase __attribute__((swift_name("phase")));
@property (readonly) NCPResourceDrainStatus *resources __attribute__((swift_name("resources")));
@property (readonly) int64_t submissionChargedBytes __attribute__((swift_name("submissionChargedBytes")));
@property (readonly) NSArray<NCPSubmissionSummary *> *submissions __attribute__((swift_name("submissions")));
@end


/** Session identity does not retain its document owner. All operations run on one logical owner. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentSession")))
@interface NCPDocumentSession : NCPBase
@property (readonly) int64_t number __attribute__((swift_name("number")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Durability")))
@interface NCPDurability : NCPKotlinEnum<NCPDurability *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPDurability *memoryOnly __attribute__((swift_name("memoryOnly")));
+ (NCPKotlinArray<NCPDurability *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPDurability *> *entries __attribute__((swift_name("entries")));
@end


/** Single surface-owner gate. Actual buffer disposal/leases are deferred to the backend phase. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FramePublicationGate")))
@interface NCPFramePublicationGate : NCPBase
- (instancetype)initWithInitial:(NCPFrameToken *)initial __attribute__((swift_name("init(initial:)"))) __attribute__((objc_designated_initializer));
- (BOOL)canPublishToken:(NCPFrameToken *)token __attribute__((swift_name("canPublish(token:)")));
- (void)close __attribute__((swift_name("close()")));
- (BOOL)expectToken:(NCPFrameToken *)token __attribute__((swift_name("expect(token:)")));
@end


/** Content identity and presentation identity are deliberately separate. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("FrameToken")))
@interface NCPFrameToken : NCPBase
- (instancetype)initWithSceneId:(NCPSceneId *)sceneId sceneRevision:(int64_t)sceneRevision viewportVersion:(int64_t)viewportVersion sessionGeneration:(int64_t)sessionGeneration surfaceGeneration:(int64_t)surfaceGeneration requestSequence:(int64_t)requestSequence __attribute__((swift_name("init(sceneId:sceneRevision:viewportVersion:sessionGeneration:surfaceGeneration:requestSequence:)"))) __attribute__((objc_designated_initializer));
- (NCPFrameToken *)doCopySceneId:(NCPSceneId *)sceneId sceneRevision:(int64_t)sceneRevision viewportVersion:(int64_t)viewportVersion sessionGeneration:(int64_t)sessionGeneration surfaceGeneration:(int64_t)surfaceGeneration requestSequence:(int64_t)requestSequence __attribute__((swift_name("doCopy(sceneId:sceneRevision:viewportVersion:sessionGeneration:surfaceGeneration:requestSequence:)")));

/** Content identity and presentation identity are deliberately separate. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Content identity and presentation identity are deliberately separate. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Content identity and presentation identity are deliberately separate. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t requestSequence __attribute__((swift_name("requestSequence")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@property (readonly) int64_t sceneRevision __attribute__((swift_name("sceneRevision")));
@property (readonly) int64_t sessionGeneration __attribute__((swift_name("sessionGeneration")));
@property (readonly) int64_t surfaceGeneration __attribute__((swift_name("surfaceGeneration")));
@property (readonly) int64_t viewportVersion __attribute__((swift_name("viewportVersion")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("GeometryCacheKey")))
@interface NCPGeometryCacheKey : NCPBase
- (instancetype)initWithSceneId:(NCPSceneId *)sceneId objectId:(NCPObjectId *)objectId objectRevision:(int64_t)objectRevision brushKind:(NCPBrushKind *)brushKind algorithmVersion:(int32_t)algorithmVersion __attribute__((swift_name("init(sceneId:objectId:objectRevision:brushKind:algorithmVersion:)"))) __attribute__((objc_designated_initializer));
- (NCPGeometryCacheKey *)doCopySceneId:(NCPSceneId *)sceneId objectId:(NCPObjectId *)objectId objectRevision:(int64_t)objectRevision brushKind:(NCPBrushKind *)brushKind algorithmVersion:(int32_t)algorithmVersion __attribute__((swift_name("doCopy(sceneId:objectId:objectRevision:brushKind:algorithmVersion:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t algorithmVersion __attribute__((swift_name("algorithmVersion")));
@property (readonly) NCPBrushKind *brushKind __attribute__((swift_name("brushKind")));
@property (readonly) NCPObjectId *objectId __attribute__((swift_name("objectId")));
@property (readonly) int64_t objectRevision __attribute__((swift_name("objectRevision")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end


/** Owns batch lists. History/coalesced real points arrive in time order; prediction is separate. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputBatch")))
@interface NCPInputBatch : NCPBase
- (instancetype)initWithSequence:(int64_t)sequence timeMicros:(int64_t)timeMicros pointerId:(int64_t)pointerId tool:(NCPPointerTool *)tool phase:(NCPPointerPhase *)phase real:(NSArray<NCPInputSample *> *)real predicted:(NSArray<NCPInputSample *> *)predicted __attribute__((swift_name("init(sequence:timeMicros:pointerId:tool:phase:real:predicted:)"))) __attribute__((objc_designated_initializer));
@property (readonly) NCPPointerPhase *phase __attribute__((swift_name("phase")));
@property (readonly) int64_t pointerId __attribute__((swift_name("pointerId")));
@property (readonly) NSArray<NCPInputSample *> *predicted __attribute__((swift_name("predicted")));
@property (readonly) NSArray<NCPInputSample *> *real __attribute__((swift_name("real")));
@property (readonly) int64_t sequence __attribute__((swift_name("sequence")));
@property (readonly) int64_t timeMicros __attribute__((swift_name("timeMicros")));
@property (readonly) NCPPointerTool *tool __attribute__((swift_name("tool")));
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
+ (NCPKotlinArray<NCPInputRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPInputRejection *> *entries __attribute__((swift_name("entries")));
@end


/** Single-owner reference accumulator with bounded primitive chunks. Not a thread-safe queue or heap cap.
 * Rejected batches leave sequence, active stroke and points unchanged; caller must surface/retry them.
 * A second stylus is rejected explicitly. Finger navigation never alters the admitted ink pointer.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputReplaySession")))
@interface NCPInputReplaySession : NCPBase
- (instancetype)initWithMaxRealSamples:(int32_t)maxRealSamples __attribute__((swift_name("init(maxRealSamples:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPInputReplaySessionCompanion *companion __attribute__((swift_name("companion")));

/** Discards only an unfinished stroke. Completed outputs belong to the caller and survive closure. */
- (void)close __attribute__((swift_name("close()")));
- (id<NCPInputResult>)consumeBatch:(NCPInputBatch *)batch targetAtDown:(NCPStrokeTarget *)targetAtDown __attribute__((swift_name("consume(batch:targetAtDown:)")));

/** Real-only producer path. Raw arrays are read synchronously and never retained.
 * Caller stays on the single owner and must not mutate buffers during this call.
 * Invalid envelopes/scalars refuse the entire batch without allocating point objects or advancing state.
 */
- (id<NCPInputResult>)consumeRealSequence:(int64_t)sequence timeMicros:(int64_t)timeMicros pointerId:(int64_t)pointerId tool:(NCPPointerTool *)tool phase:(NCPPointerPhase *)phase xs:(NCPKotlinDoubleArray *)xs ys:(NCPKotlinDoubleArray *)ys pressures:(NCPKotlinFloatArray *)pressures times:(NCPKotlinLongArray *)times targetAtDown:(NCPStrokeTarget *)targetAtDown __attribute__((swift_name("consumeReal(sequence:timeMicros:pointerId:tool:phase:xs:ys:pressures:times:targetAtDown:)")));
@property (readonly) int64_t activePointByteCharge __attribute__((swift_name("activePointByteCharge")));
@property (readonly) int32_t activeRealSamples __attribute__((swift_name("activeRealSamples")));
@property (readonly) int64_t reservedByteCharge __attribute__((swift_name("reservedByteCharge")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputReplaySession.Companion")))
@interface NCPInputReplaySessionCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPInputReplaySessionCompanion *shared __attribute__((swift_name("shared")));

/** Arena plus immutable completion payload and declared overhead, not measured heap. */
- (int64_t)reservationBytesMaxRealSamples:(int32_t)maxRealSamples __attribute__((swift_name("reservationBytes(maxRealSamples:)")));
@end

__attribute__((swift_name("InputResult")))
@protocol NCPInputResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputResultCancelled")))
@interface NCPInputResultCancelled : NCPBase <NCPInputResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)cancelled __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPInputResultCancelled *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputResultCompleted")))
@interface NCPInputResultCompleted : NCPBase <NCPInputResult>
- (instancetype)initWithStroke:(NCPCompletedInput *)stroke __attribute__((swift_name("init(stroke:)"))) __attribute__((objc_designated_initializer));
- (NCPInputResultCompleted *)doCopyStroke:(NCPCompletedInput *)stroke __attribute__((swift_name("doCopy(stroke:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCompletedInput *stroke __attribute__((swift_name("stroke")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputResultIgnoredNavigation")))
@interface NCPInputResultIgnoredNavigation : NCPBase <NCPInputResult>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)ignoredNavigation __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPInputResultIgnoredNavigation *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputResultPreview")))
@interface NCPInputResultPreview : NCPBase <NCPInputResult>
- (instancetype)initWithRealCount:(int32_t)realCount prediction:(NSArray<NCPInputSample *> *)prediction __attribute__((swift_name("init(realCount:prediction:)"))) __attribute__((objc_designated_initializer));
- (NCPInputResultPreview *)doCopyRealCount:(int32_t)realCount prediction:(NSArray<NCPInputSample *> *)prediction __attribute__((swift_name("doCopy(realCount:prediction:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSArray<NCPInputSample *> *prediction __attribute__((swift_name("prediction")));
@property (readonly) int32_t realCount __attribute__((swift_name("realCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputResultRejected")))
@interface NCPInputResultRejected : NCPBase <NCPInputResult>
- (instancetype)initWithReason:(NCPInputRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPInputResultRejected *)doCopyReason:(NCPInputRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPInputRejection *reason __attribute__((swift_name("reason")));
@end


/** Scene coordinates and monotonic microseconds, already normalized by a platform adapter. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("InputSample")))
@interface NCPInputSample : NCPBase
- (instancetype)initWithX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("init(x:y:pressure:timeMicros:)"))) __attribute__((objc_designated_initializer));
- (NCPInputSample *)doCopyX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("doCopy(x:y:pressure:timeMicros:)")));

/** Scene coordinates and monotonic microseconds, already normalized by a platform adapter. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Scene coordinates and monotonic microseconds, already normalized by a platform adapter. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Scene coordinates and monotonic microseconds, already normalized by a platform adapter. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) float pressure __attribute__((swift_name("pressure")));
@property (readonly) int64_t timeMicros __attribute__((swift_name("timeMicros")));
@property (readonly) double x __attribute__((swift_name("x")));
@property (readonly) double y __attribute__((swift_name("y")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("LayerId")))
@interface NCPLayerId : NCPBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPLayerId *)doCopyValue:(NSString *)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("LeaseReturn")))
@interface NCPLeaseReturn : NCPKotlinEnum<NCPLeaseReturn *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPLeaseReturn *returned __attribute__((swift_name("returned")));
@property (class, readonly) NCPLeaseReturn *alreadyReturned __attribute__((swift_name("alreadyReturned")));
@property (class, readonly) NCPLeaseReturn *foreign __attribute__((swift_name("foreign")));
+ (NCPKotlinArray<NCPLeaseReturn *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPLeaseReturn *> *entries __attribute__((swift_name("entries")));
@end


/** Single-owner C1 host reference. Does not implement disk durability, multithreading or undo. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MemorySceneCoordinator")))
@interface NCPMemorySceneCoordinator : NCPBase
- (instancetype)initWithInitial:(NCPSceneSnapshot *)initial journalCapacity:(int32_t)journalCapacity __attribute__((swift_name("init(initial:journalCapacity:)"))) __attribute__((objc_designated_initializer));
- (id<NCPCommitResult>)commitPatch:(NCPScenePatch *)patch __attribute__((swift_name("commit(patch:)")));
- (NCPSceneSnapshot *)snapshot __attribute__((swift_name("snapshot()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ObjectChange")))
@interface NCPObjectChange : NCPBase
- (instancetype)initWithBefore:(NCPStrokeObject * _Nullable)before after:(NCPStrokeObject * _Nullable)after __attribute__((swift_name("init(before:after:)"))) __attribute__((objc_designated_initializer));
- (NCPObjectChange *)doCopyBefore:(NCPStrokeObject * _Nullable)before after:(NCPStrokeObject * _Nullable)after __attribute__((swift_name("doCopy(before:after:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPStrokeObject * _Nullable after __attribute__((swift_name("after")));
@property (readonly) NCPStrokeObject * _Nullable before __attribute__((swift_name("before")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ObjectId")))
@interface NCPObjectId : NCPBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPObjectId *)doCopyValue:(NSString *)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("OwnedWorkStage")))
@interface NCPOwnedWorkStage : NCPKotlinEnum<NCPOwnedWorkStage *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPOwnedWorkStage *queued __attribute__((swift_name("queued")));
@property (class, readonly) NCPOwnedWorkStage *running __attribute__((swift_name("running")));
@property (class, readonly) NCPOwnedWorkStage *ready __attribute__((swift_name("ready")));
@property (class, readonly) NCPOwnedWorkStage *delivering __attribute__((swift_name("delivering")));
@property (class, readonly) NCPOwnedWorkStage *deliveryBlocked __attribute__((swift_name("deliveryBlocked")));
+ (NCPKotlinArray<NCPOwnedWorkStage *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPOwnedWorkStage *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("OwnedWorkSummary")))
@interface NCPOwnedWorkSummary : NCPBase
- (instancetype)initWithTicket:(NCPOwnerWorkTicket *)ticket stage:(NCPOwnedWorkStage *)stage chargedBytes:(int64_t)chargedBytes deliveryFailure:(NSString * _Nullable)deliveryFailure __attribute__((swift_name("init(ticket:stage:chargedBytes:deliveryFailure:)"))) __attribute__((objc_designated_initializer));
- (NCPOwnedWorkSummary *)doCopyTicket:(NCPOwnerWorkTicket *)ticket stage:(NCPOwnedWorkStage *)stage chargedBytes:(int64_t)chargedBytes deliveryFailure:(NSString * _Nullable)deliveryFailure __attribute__((swift_name("doCopy(ticket:stage:chargedBytes:deliveryFailure:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) NSString * _Nullable deliveryFailure __attribute__((swift_name("deliveryFailure")));
@property (readonly) NCPOwnedWorkStage *stage __attribute__((swift_name("stage")));
@property (readonly) NCPOwnerWorkTicket *ticket __attribute__((swift_name("ticket")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("OwnerExecutorStatus")))
@interface NCPOwnerExecutorStatus : NCPBase
- (instancetype)initWithAccepting:(BOOL)accepting workerStopped:(BOOL)workerStopped workerFailure:(NSString * _Nullable)workerFailure chargedBytes:(int64_t)chargedBytes entries:(NSArray<NCPOwnedWorkSummary *> *)entries ownerThreadId:(int64_t)ownerThreadId workerThreadId:(int64_t)workerThreadId ownerWakeFailure:(NSString * _Nullable)ownerWakeFailure __attribute__((swift_name("init(accepting:workerStopped:workerFailure:chargedBytes:entries:ownerThreadId:workerThreadId:ownerWakeFailure:)"))) __attribute__((objc_designated_initializer));
- (NCPOwnerExecutorStatus *)doCopyAccepting:(BOOL)accepting workerStopped:(BOOL)workerStopped workerFailure:(NSString * _Nullable)workerFailure chargedBytes:(int64_t)chargedBytes entries:(NSArray<NCPOwnedWorkSummary *> *)entries ownerThreadId:(int64_t)ownerThreadId workerThreadId:(int64_t)workerThreadId ownerWakeFailure:(NSString * _Nullable)ownerWakeFailure __attribute__((swift_name("doCopy(accepting:workerStopped:workerFailure:chargedBytes:entries:ownerThreadId:workerThreadId:ownerWakeFailure:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) BOOL accepting __attribute__((swift_name("accepting")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) NSArray<NCPOwnedWorkSummary *> *entries __attribute__((swift_name("entries")));
@property (readonly) BOOL isDrained __attribute__((swift_name("isDrained")));
@property (readonly) int64_t ownerThreadId __attribute__((swift_name("ownerThreadId")));
@property (readonly) NSString * _Nullable ownerWakeFailure __attribute__((swift_name("ownerWakeFailure")));
@property (readonly) NSString * _Nullable workerFailure __attribute__((swift_name("workerFailure")));
@property (readonly) BOOL workerStopped __attribute__((swift_name("workerStopped")));
@property (readonly) int64_t workerThreadId __attribute__((swift_name("workerThreadId")));
@end


/** Opaque identity does not retain closures, payloads, results or an executor. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("OwnerWorkTicket")))
@interface NCPOwnerWorkTicket : NCPBase
@property (readonly) int64_t number __attribute__((swift_name("number")));
@end


/** C1 reference geometry, not a frozen public API or a rendering implementation. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PointD")))
@interface NCPPointD : NCPBase
- (instancetype)initWithX:(double)x y:(double)y __attribute__((swift_name("init(x:y:)"))) __attribute__((objc_designated_initializer));
- (NCPPointD *)doCopyX:(double)x y:(double)y __attribute__((swift_name("doCopy(x:y:)")));

/** C1 reference geometry, not a frozen public API or a rendering implementation. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** C1 reference geometry, not a frozen public API or a rendering implementation. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** C1 reference geometry, not a frozen public API or a rendering implementation. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) double x __attribute__((swift_name("x")));
@property (readonly) double y __attribute__((swift_name("y")));
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
+ (NCPKotlinArray<NCPPointerPhase *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPointerPhase *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PointerTool")))
@interface NCPPointerTool : NCPKotlinEnum<NCPPointerTool *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPointerTool *stylus __attribute__((swift_name("stylus")));
@property (class, readonly) NCPPointerTool *finger __attribute__((swift_name("finger")));
+ (NCPKotlinArray<NCPPointerTool *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPointerTool *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("PrepareResult")))
@protocol NCPPrepareResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PrepareResultPrepared")))
@interface NCPPrepareResultPrepared : NCPBase <NCPPrepareResult>
- (instancetype)initWithPatch:(NCPScenePatch *)patch __attribute__((swift_name("init(patch:)"))) __attribute__((objc_designated_initializer));
- (NCPPrepareResultPrepared *)doCopyPatch:(NCPScenePatch *)patch __attribute__((swift_name("doCopy(patch:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPScenePatch *patch __attribute__((swift_name("patch")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PrepareResultRejected")))
@interface NCPPrepareResultRejected : NCPBase <NCPPrepareResult>
- (instancetype)initWithReason:(NCPRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPPrepareResultRejected *)doCopyReason:(NCPRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@end


/** Offer is read-only. A receiver must retain the payload BEFORE acknowledging ownership. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("RecoveryOffer")))
@interface NCPRecoveryOffer : NCPBase
@property (readonly) id<NCPRecoveryPayload> payload __attribute__((swift_name("payload")));
@end

__attribute__((swift_name("RecoveryPayload")))
@protocol NCPRecoveryPayload
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("RecoveryPayloadBlocked")))
@interface NCPRecoveryPayloadBlocked : NCPBase <NCPRecoveryPayload>
- (instancetype)initWithSubmission:(NCPStrokeSubmission *)submission reason:(NCPRejection *)reason __attribute__((swift_name("init(submission:reason:)"))) __attribute__((objc_designated_initializer));
- (NCPRecoveryPayloadBlocked *)doCopySubmission:(NCPStrokeSubmission *)submission reason:(NCPRejection *)reason __attribute__((swift_name("doCopy(submission:reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@property (readonly) NCPStrokeSubmission *submission __attribute__((swift_name("submission")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("RecoveryPayloadConfirmed")))
@interface NCPRecoveryPayloadConfirmed : NCPBase <NCPRecoveryPayload>
- (instancetype)initWithSubmission:(NCPStrokeSubmission *)submission receipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("init(submission:receipt:)"))) __attribute__((objc_designated_initializer));
- (NCPRecoveryPayloadConfirmed *)doCopySubmission:(NCPStrokeSubmission *)submission receipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("doCopy(submission:receipt:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommitReceipt *receipt __attribute__((swift_name("receipt")));
@property (readonly) NCPStrokeSubmission *submission __attribute__((swift_name("submission")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("RecoveryPayloadPending")))
@interface NCPRecoveryPayloadPending : NCPBase <NCPRecoveryPayload>
- (instancetype)initWithSession:(NCPDocumentSession *)session completed:(NCPCompletedInput *)completed proposal:(NCPStrokeSubmission * _Nullable)proposal lastAdmissionFailure:(NCPAdmissionRejection * _Nullable)lastAdmissionFailure __attribute__((swift_name("init(session:completed:proposal:lastAdmissionFailure:)"))) __attribute__((objc_designated_initializer));
- (NCPRecoveryPayloadPending *)doCopySession:(NCPDocumentSession *)session completed:(NCPCompletedInput *)completed proposal:(NCPStrokeSubmission * _Nullable)proposal lastAdmissionFailure:(NCPAdmissionRejection * _Nullable)lastAdmissionFailure __attribute__((swift_name("doCopy(session:completed:proposal:lastAdmissionFailure:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCompletedInput *completed __attribute__((swift_name("completed")));
@property (readonly) NCPAdmissionRejection * _Nullable lastAdmissionFailure __attribute__((swift_name("lastAdmissionFailure")));
@property (readonly) NCPStrokeSubmission * _Nullable proposal __attribute__((swift_name("proposal")));
@property (readonly) NCPDocumentSession *session __attribute__((swift_name("session")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("Rejection")))
@interface NCPRejection : NCPKotlinEnum<NCPRejection *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPRejection *wrongScene __attribute__((swift_name("wrongScene")));
@property (class, readonly) NCPRejection *staleScene __attribute__((swift_name("staleScene")));
@property (class, readonly) NCPRejection *missingObject __attribute__((swift_name("missingObject")));
@property (class, readonly) NCPRejection *duplicateObject __attribute__((swift_name("duplicateObject")));
@property (class, readonly) NCPRejection *staleObject __attribute__((swift_name("staleObject")));
@property (class, readonly) NCPRejection *invalidGeometry __attribute__((swift_name("invalidGeometry")));
@property (class, readonly) NCPRejection *revisionExhausted __attribute__((swift_name("revisionExhausted")));
@property (class, readonly) NCPRejection *reusedCommandId __attribute__((swift_name("reusedCommandId")));
@property (class, readonly) NCPRejection *journalFull __attribute__((swift_name("journalFull")));
+ (NCPKotlinArray<NCPRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPRejection *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ReservationFailure")))
@interface NCPReservationFailure : NCPKotlinEnum<NCPReservationFailure *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPReservationFailure *closed __attribute__((swift_name("closed")));
@property (class, readonly) NCPReservationFailure *invalidBytes __attribute__((swift_name("invalidBytes")));
@property (class, readonly) NCPReservationFailure *residentLimit __attribute__((swift_name("residentLimit")));
@property (class, readonly) NCPReservationFailure *totalLimit __attribute__((swift_name("totalLimit")));
@property (class, readonly) NCPReservationFailure *resourceCount __attribute__((swift_name("resourceCount")));
+ (NCPKotlinArray<NCPReservationFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPReservationFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("ReservationResult")))
@protocol NCPReservationResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ReservationResultRejected")))
@interface NCPReservationResultRejected : NCPBase <NCPReservationResult>
- (instancetype)initWithReason:(NCPReservationFailure *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPReservationResultRejected *)doCopyReason:(NCPReservationFailure *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPReservationFailure *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ReservationResultReserved")))
@interface NCPReservationResultReserved : NCPBase <NCPReservationResult>
- (instancetype)initWithReservation:(NCPResourceReservation *)reservation __attribute__((swift_name("init(reservation:)"))) __attribute__((objc_designated_initializer));
- (NCPReservationResultReserved *)doCopyReservation:(NCPResourceReservation *)reservation __attribute__((swift_name("doCopy(reservation:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceReservation *reservation __attribute__((swift_name("reservation")));
@end

__attribute__((swift_name("ResourceBorrow")))
@protocol NCPResourceBorrow
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceBorrowBorrowed")))
@interface NCPResourceBorrowBorrowed<V> : NCPBase <NCPResourceBorrow>
- (instancetype)initWithLease:(NCPResourceLease<V> *)lease __attribute__((swift_name("init(lease:)"))) __attribute__((objc_designated_initializer));
- (NCPResourceBorrowBorrowed<V> *)doCopyLease:(NCPResourceLease<V> *)lease __attribute__((swift_name("doCopy(lease:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceLease<V> *lease __attribute__((swift_name("lease")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceBorrowClosed")))
@interface NCPResourceBorrowClosed : NCPBase <NCPResourceBorrow>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)closed __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourceBorrowClosed *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceBorrowLeaseLimit")))
@interface NCPResourceBorrowLeaseLimit : NCPBase <NCPResourceBorrow>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)leaseLimit __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourceBorrowLeaseLimit *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceBorrowMissing")))
@interface NCPResourceBorrowMissing : NCPBase <NCPResourceBorrow>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)missing __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourceBorrowMissing *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceCompletion")))
@interface NCPResourceCompletion : NCPKotlinEnum<NCPResourceCompletion *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPResourceCompletion *updated __attribute__((swift_name("updated")));
@property (class, readonly) NCPResourceCompletion *stale __attribute__((swift_name("stale")));
+ (NCPKotlinArray<NCPResourceCompletion *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPResourceCompletion *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceDrainStatus")))
@interface NCPResourceDrainStatus : NCPBase
- (instancetype)initWithReservations:(int32_t)reservations readers:(int32_t)readers releasing:(int32_t)releasing unknown:(NSArray<NCPResourceHandle *> *)unknown ready:(int32_t)ready chargedBytes:(int64_t)chargedBytes isDrained:(BOOL)isDrained __attribute__((swift_name("init(reservations:readers:releasing:unknown:ready:chargedBytes:isDrained:)"))) __attribute__((objc_designated_initializer));
- (NCPResourceDrainStatus *)doCopyReservations:(int32_t)reservations readers:(int32_t)readers releasing:(int32_t)releasing unknown:(NSArray<NCPResourceHandle *> *)unknown ready:(int32_t)ready chargedBytes:(int64_t)chargedBytes isDrained:(BOOL)isDrained __attribute__((swift_name("doCopy(reservations:readers:releasing:unknown:ready:chargedBytes:isDrained:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) BOOL isDrained __attribute__((swift_name("isDrained")));
@property (readonly) int32_t readers __attribute__((swift_name("readers")));
@property (readonly) int32_t ready __attribute__((swift_name("ready")));
@property (readonly) int32_t releasing __attribute__((swift_name("releasing")));
@property (readonly) int32_t reservations __attribute__((swift_name("reservations")));
@property (readonly) NSArray<NCPResourceHandle *> *unknown __attribute__((swift_name("unknown")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceHandle")))
@interface NCPResourceHandle : NCPBase
@property (readonly) int64_t bytes __attribute__((swift_name("bytes")));
@end


/** Logical dispatch destination, not evidence of an actual platform thread. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceLane")))
@interface NCPResourceLane : NCPKotlinEnum<NCPResourceLane *>
+ (instancetype)alloc __attribute__((unavailable));

/** Logical dispatch destination, not evidence of an actual platform thread. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPResourceLane *owner __attribute__((swift_name("owner")));
@property (class, readonly) NCPResourceLane *worker __attribute__((swift_name("worker")));
@property (class, readonly) NCPResourceLane *gpu __attribute__((swift_name("gpu")));
+ (NCPKotlinArray<NCPResourceLane *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPResourceLane *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceLease")))
@interface NCPResourceLease<V> : NCPBase
@property (readonly) V value __attribute__((swift_name("value")));
@end


/** Single-owner C1 lifecycle model. Counts reservations and retired/in-flight/unknown releases until
 * the backend confirms destruction. Declared bytes are not measured heap/GPU footprint.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceLeasePool")))
@interface NCPResourceLeasePool<K, V> : NCPBase
- (instancetype)initWithResidentBudget:(int64_t)residentBudget totalBudget:(int64_t)totalBudget maxResources:(int32_t)maxResources maxLeases:(int32_t)maxLeases releaseLane:(NCPResourceLane *)releaseLane __attribute__((swift_name("init(residentBudget:totalBudget:maxResources:maxLeases:releaseLane:)"))) __attribute__((objc_designated_initializer));
- (id<NCPResourceBorrow>)borrowKey:(K)key __attribute__((swift_name("borrow(key:)")));

/** Only when allocation definitely did not occur. Close cannot assume a pending worker stopped. */
- (BOOL)cancelUnallocatedReservation:(NCPResourceReservation *)reservation __attribute__((swift_name("cancelUnallocated(reservation:)")));

/** Terminal: readers and reservations still own lifetime obligations and remain charged. */
- (void)close __attribute__((swift_name("close()")));

/** Route backend outcomes back to this owner. Duplicate/old/foreign tickets never release budget. */
- (NCPResourceCompletion *)completeReleaseTicket:(NCPResourceReleaseTicket<V> *)ticket outcome:(NCPResourceReleaseOutcome *)outcome __attribute__((swift_name("completeRelease(ticket:outcome:)")));
- (NCPResourceDrainStatus *)drainStatus __attribute__((swift_name("drainStatus()")));
- (BOOL)evictKey:(K)key __attribute__((swift_name("evict(key:)")));
- (void)evictAll __attribute__((swift_name("evictAll()")));

/** Owner schedules the returned ticket on the designated lane; no destructor runs here. */
- (id<NCPResourceReleaseDispatch>)nextReleaseDestination:(NCPResourceLane *)destination __attribute__((swift_name("nextRelease(destination:)")));

/** Successful publication transfers ownership, even if a close requires immediate retirement. */
- (id<NCPResourcePublication>)publishReservation:(NCPResourceReservation *)reservation key:(K)key value:(V)value __attribute__((swift_name("publish(reservation:key:value:)")));

/** Allocated but obsolete output transfers directly to retirement, without replacing a newer
 * resident entry. The ordinary release/confirmation protocol still applies.
 */
- (id<NCPResourcePublication>)publishRetiredReservation:(NCPResourceReservation *)reservation value:(V)value __attribute__((swift_name("publishRetired(reservation:value:)")));

/** Reserve BEFORE allocating. Rejecting a replacement does not evict the old resident value. */
- (id<NCPReservationResult>)reserveBytes:(int64_t)bytes __attribute__((swift_name("reserve(bytes:)")));

/** Worker peak may exceed resident budget, but total admission includes that full peak.
 * Must settle to at most retainedBytes after execution/ACK before any publication.
 */
- (id<NCPReservationResult>)reservePeakBytes:(int64_t)bytes retainedBytes:(int64_t)retainedBytes __attribute__((swift_name("reservePeak(bytes:retainedBytes:)")));

/** Explicit backend reconciliation only; UNKNOWN cannot resolve an UNKNOWN destruction outcome. */
- (NCPResourceCompletion *)resolveUnknownHandle:(NCPResourceHandle *)handle outcome:(NCPResourceReleaseOutcome *)outcome __attribute__((swift_name("resolveUnknown(handle:outcome:)")));
- (NCPLeaseReturn *)returnLeaseLease:(NCPResourceLease<V> *)lease __attribute__((swift_name("returnLease(lease:)")));

/** Owner confirms work/input handoff has ended before reducing a peak reservation to retained
 * output. Old token becomes stale. Never use this merely because cancellation was requested.
 */
- (NCPResourceReservation * _Nullable)settleReservationReservation:(NCPResourceReservation *)reservation retainedBytes:(int64_t)retainedBytes __attribute__((swift_name("settleReservation(reservation:retainedBytes:)")));

/** Unique resource bytes referenced by readers; overlaps resident/retired and must not be summed. */
@property (readonly) int64_t borrowedBytes __attribute__((swift_name("borrowedBytes")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) BOOL isDrained __attribute__((swift_name("isDrained")));
@property (readonly) int32_t maxLeases __attribute__((swift_name("maxLeases")));
@property (readonly) int32_t maxResources __attribute__((swift_name("maxResources")));
@property (readonly) int32_t outstandingLeases __attribute__((swift_name("outstandingLeases")));
@property (readonly) NCPResourceLane *releaseLane __attribute__((swift_name("releaseLane")));
@property (readonly) int64_t reservedBytes __attribute__((swift_name("reservedBytes")));
@property (readonly) int64_t residentBudget __attribute__((swift_name("residentBudget")));
@property (readonly) int64_t residentBytes __attribute__((swift_name("residentBytes")));
@property (readonly) int32_t retainedResources __attribute__((swift_name("retainedResources")));
@property (readonly) int64_t retiredBytes __attribute__((swift_name("retiredBytes")));
@property (readonly) int64_t totalBudget __attribute__((swift_name("totalBudget")));
@end

__attribute__((swift_name("ResourcePublication")))
@protocol NCPResourcePublication
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationAlreadyOwned")))
@interface NCPResourcePublicationAlreadyOwned : NCPBase <NCPResourcePublication>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)alreadyOwned __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourcePublicationAlreadyOwned *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationResident")))
@interface NCPResourcePublicationResident : NCPBase <NCPResourcePublication>
- (instancetype)initWithHandle:(NCPResourceHandle *)handle __attribute__((swift_name("init(handle:)"))) __attribute__((objc_designated_initializer));
- (NCPResourcePublicationResident *)doCopyHandle:(NCPResourceHandle *)handle __attribute__((swift_name("doCopy(handle:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceHandle *handle __attribute__((swift_name("handle")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationRetired")))
@interface NCPResourcePublicationRetired : NCPBase <NCPResourcePublication>
- (instancetype)initWithHandle:(NCPResourceHandle *)handle __attribute__((swift_name("init(handle:)"))) __attribute__((objc_designated_initializer));
- (NCPResourcePublicationRetired *)doCopyHandle:(NCPResourceHandle *)handle __attribute__((swift_name("doCopy(handle:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceHandle *handle __attribute__((swift_name("handle")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationRetiredAfterClose")))
@interface NCPResourcePublicationRetiredAfterClose : NCPBase <NCPResourcePublication>
- (instancetype)initWithHandle:(NCPResourceHandle *)handle __attribute__((swift_name("init(handle:)"))) __attribute__((objc_designated_initializer));
- (NCPResourcePublicationRetiredAfterClose *)doCopyHandle:(NCPResourceHandle *)handle __attribute__((swift_name("doCopy(handle:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceHandle *handle __attribute__((swift_name("handle")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationStaleReservation")))
@interface NCPResourcePublicationStaleReservation : NCPBase <NCPResourcePublication>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)staleReservation __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourcePublicationStaleReservation *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourcePublicationUnsettledReservation")))
@interface NCPResourcePublicationUnsettledReservation : NCPBase <NCPResourcePublication>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)unsettledReservation __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourcePublicationUnsettledReservation *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((swift_name("ResourceReleaseDispatch")))
@protocol NCPResourceReleaseDispatch
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReleaseDispatchIdle")))
@interface NCPResourceReleaseDispatchIdle : NCPBase <NCPResourceReleaseDispatch>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)idle __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourceReleaseDispatchIdle *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReleaseDispatchReady")))
@interface NCPResourceReleaseDispatchReady<V> : NCPBase <NCPResourceReleaseDispatch>
- (instancetype)initWithTicket:(NCPResourceReleaseTicket<V> *)ticket __attribute__((swift_name("init(ticket:)"))) __attribute__((objc_designated_initializer));
- (NCPResourceReleaseDispatchReady<V> *)doCopyTicket:(NCPResourceReleaseTicket<V> *)ticket __attribute__((swift_name("doCopy(ticket:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPResourceReleaseTicket<V> *ticket __attribute__((swift_name("ticket")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReleaseDispatchWrongLane")))
@interface NCPResourceReleaseDispatchWrongLane : NCPBase <NCPResourceReleaseDispatch>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)wrongLane __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPResourceReleaseDispatchWrongLane *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReleaseOutcome")))
@interface NCPResourceReleaseOutcome : NCPKotlinEnum<NCPResourceReleaseOutcome *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPResourceReleaseOutcome *released __attribute__((swift_name("released")));
@property (class, readonly) NCPResourceReleaseOutcome *notReleased __attribute__((swift_name("notReleased")));
@property (class, readonly) NCPResourceReleaseOutcome *unknown __attribute__((swift_name("unknown")));
+ (NCPKotlinArray<NCPResourceReleaseOutcome *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPResourceReleaseOutcome *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReleaseTicket")))
@interface NCPResourceReleaseTicket<V> : NCPBase
@property (readonly) NCPResourceHandle *handle __attribute__((swift_name("handle")));
@property (readonly) V value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResourceReservation")))
@interface NCPResourceReservation : NCPBase
@property (readonly) int64_t bytes __attribute__((swift_name("bytes")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResultDisposition")))
@interface NCPResultDisposition : NCPKotlinEnum<NCPResultDisposition *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPResultDisposition *acknowledged __attribute__((swift_name("acknowledged")));
@property (class, readonly) NCPResultDisposition *retryRequired __attribute__((swift_name("retryRequired")));
+ (NCPKotlinArray<NCPResultDisposition *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPResultDisposition *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ResultDrain")))
@interface NCPResultDrain : NCPBase
- (instancetype)initWithAcknowledged:(int32_t)acknowledged blocked:(NCPOwnerWorkTicket * _Nullable)blocked __attribute__((swift_name("init(acknowledged:blocked:)"))) __attribute__((objc_designated_initializer));
- (NCPResultDrain *)doCopyAcknowledged:(int32_t)acknowledged blocked:(NCPOwnerWorkTicket * _Nullable)blocked __attribute__((swift_name("doCopy(acknowledged:blocked:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t acknowledged __attribute__((swift_name("acknowledged")));
@property (readonly) NCPOwnerWorkTicket * _Nullable blocked __attribute__((swift_name("blocked")));
@end

__attribute__((swift_name("SceneApplication")))
@protocol NCPSceneApplication
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneApplicationApplied")))
@interface NCPSceneApplicationApplied : NCPBase <NCPSceneApplication>
- (instancetype)initWithSnapshot:(NCPSceneSnapshot *)snapshot __attribute__((swift_name("init(snapshot:)"))) __attribute__((objc_designated_initializer));
- (NCPSceneApplicationApplied *)doCopySnapshot:(NCPSceneSnapshot *)snapshot __attribute__((swift_name("doCopy(snapshot:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPSceneSnapshot *snapshot __attribute__((swift_name("snapshot")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneApplicationRejected")))
@interface NCPSceneApplicationRejected : NCPBase <NCPSceneApplication>
- (instancetype)initWithReason:(NCPRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPSceneApplicationRejected *)doCopyReason:(NCPRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneId")))
@interface NCPSceneId : NCPBase
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPSceneId *)doCopyValue:(NSString *)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ScenePatch")))
@interface NCPScenePatch : NCPBase
- (NSArray<NCPObjectChange *> *)changes __attribute__((swift_name("changes()")));
- (BOOL)sameContentOther:(NCPScenePatch *)other __attribute__((swift_name("sameContent(other:)")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPCommandId *commandId __attribute__((swift_name("commandId")));
@property (readonly) NCPBoundsD *dirtyBounds __attribute__((swift_name("dirtyBounds")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneReducer")))
@interface NCPSceneReducer : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)sceneReducer __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPSceneReducer *shared __attribute__((swift_name("shared")));
- (id<NCPSceneApplication>)applyScene:(NCPSceneSnapshot *)scene patch:(NCPScenePatch *)patch __attribute__((swift_name("apply(scene:patch:)")));
- (id<NCPPrepareResult>)prepareScene:(NCPSceneSnapshot *)scene command:(id<NCPCanvasCommand>)command __attribute__((swift_name("prepare(scene:command:)")));

/** Pure composition for an outer document authority; never owns mutable scene state or a receipt journal. */
- (id<NCPPrepareResult>)prepareChangesScene:(NCPSceneSnapshot *)scene commandId:(NCPCommandId *)commandId changes:(NSArray<NCPObjectChange *> *)changes metadataDirtyBounds:(NCPBoundsD * _Nullable)metadataDirtyBounds __attribute__((swift_name("prepareChanges(scene:commandId:changes:metadataDirtyBounds:)")));
@end


/** A small immutable reference snapshot. Map copying is not the planned production store. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneSnapshot")))
@interface NCPSceneSnapshot : NCPBase
@property (class, readonly, getter=companion) NCPSceneSnapshotCompanion *companion __attribute__((swift_name("companion")));
- (NCPStrokeObject * _Nullable)findId:(NCPObjectId *)id __attribute__((swift_name("find(id:)")));

/** Synchronous immutable inspection without a full-scene list copy; never exposes the map.
 * Unfrozen snapshot read surface. Callers own any references retained by their visitor.
 */
- (void)forEachObjectVisit:(void (^)(NCPStrokeObject *))visit __attribute__((swift_name("forEachObject(visit:)")));
- (NSArray<NCPStrokeObject *> *)objects __attribute__((swift_name("objects()")));

/** Immutable content comparison preserves draw order without two full-scene reference lists.
 * This is a read operation, not an authority or an unversioned cache identity.
 */
- (BOOL)sameContentOther:(NCPSceneSnapshot *)other __attribute__((swift_name("sameContent(other:)")));
@property (readonly) NCPSceneId *id __attribute__((swift_name("id")));
@property (readonly) int32_t objectCount __attribute__((swift_name("objectCount")));
@property (readonly) int64_t revision __attribute__((swift_name("revision")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SceneSnapshot.Companion")))
@interface NCPSceneSnapshotCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPSceneSnapshotCompanion *shared __attribute__((swift_name("shared")));
- (NCPSceneSnapshot *)emptyId:(NCPSceneId *)id __attribute__((swift_name("empty(id:)")));

/** Read-only composition preserving explicit draw order, without rescanning immutable sample geometry. */
- (NCPSceneSnapshot *)reorderedScene:(NCPSceneSnapshot *)scene objectOrder:(NSArray<NCPObjectId *> *)objectOrder __attribute__((swift_name("reordered(scene:objectOrder:)")));

/** Validated codec/fixture entry, not a mutable authority or a claim of persisted durability. */
- (NCPSceneSnapshot *)restoredId:(NCPSceneId *)id revision:(int64_t)revision objects:(NSArray<NCPStrokeObject *> *)objects __attribute__((swift_name("restored(id:revision:objects:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionInputFailure")))
@interface NCPSessionInputFailure : NCPKotlinEnum<NCPSessionInputFailure *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPSessionInputFailure *closing __attribute__((swift_name("closing")));
@property (class, readonly) NCPSessionInputFailure *closedOrForeign __attribute__((swift_name("closedOrForeign")));
@property (class, readonly) NCPSessionInputFailure *completedPending __attribute__((swift_name("completedPending")));
@property (class, readonly) NCPSessionInputFailure *wrongScene __attribute__((swift_name("wrongScene")));
+ (NCPKotlinArray<NCPSessionInputFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPSessionInputFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("SessionInputResult")))
@protocol NCPSessionInputResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionInputResultConsumed")))
@interface NCPSessionInputResultConsumed : NCPBase <NCPSessionInputResult>
- (instancetype)initWithResult:(id<NCPInputResult>)result __attribute__((swift_name("init(result:)"))) __attribute__((objc_designated_initializer));
- (NCPSessionInputResultConsumed *)doCopyResult:(id<NCPInputResult>)result __attribute__((swift_name("doCopy(result:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) id<NCPInputResult> result __attribute__((swift_name("result")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionInputResultRejected")))
@interface NCPSessionInputResultRejected : NCPBase <NCPSessionInputResult>
- (instancetype)initWithReason:(NCPSessionInputFailure *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPSessionInputResultRejected *)doCopyReason:(NCPSessionInputFailure *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPSessionInputFailure *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionOpenFailure")))
@interface NCPSessionOpenFailure : NCPKotlinEnum<NCPSessionOpenFailure *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPSessionOpenFailure *closing __attribute__((swift_name("closing")));
@property (class, readonly) NCPSessionOpenFailure *wrongScene __attribute__((swift_name("wrongScene")));
@property (class, readonly) NCPSessionOpenFailure *staleFrame __attribute__((swift_name("staleFrame")));
@property (class, readonly) NCPSessionOpenFailure *sessionLimit __attribute__((swift_name("sessionLimit")));
@property (class, readonly) NCPSessionOpenFailure *inputBudget __attribute__((swift_name("inputBudget")));
@property (class, readonly) NCPSessionOpenFailure *idExhausted __attribute__((swift_name("idExhausted")));
+ (NCPKotlinArray<NCPSessionOpenFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPSessionOpenFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("SessionOpenResult")))
@protocol NCPSessionOpenResult
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionOpenResultOpened")))
@interface NCPSessionOpenResultOpened : NCPBase <NCPSessionOpenResult>
- (instancetype)initWithSession:(NCPDocumentSession *)session __attribute__((swift_name("init(session:)"))) __attribute__((objc_designated_initializer));
- (NCPSessionOpenResultOpened *)doCopySession:(NCPDocumentSession *)session __attribute__((swift_name("doCopy(session:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPDocumentSession *session __attribute__((swift_name("session")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionOpenResultRejected")))
@interface NCPSessionOpenResultRejected : NCPBase <NCPSessionOpenResult>
- (instancetype)initWithReason:(NCPSessionOpenFailure *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPSessionOpenResultRejected *)doCopyReason:(NCPSessionOpenFailure *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPSessionOpenFailure *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SessionStop")))
@interface NCPSessionStop : NCPBase
- (instancetype)initWithSession:(NCPDocumentSession *)session cancelledRealSamples:(int32_t)cancelledRealSamples completedPending:(BOOL)completedPending __attribute__((swift_name("init(session:cancelledRealSamples:completedPending:)"))) __attribute__((objc_designated_initializer));
- (NCPSessionStop *)doCopySession:(NCPDocumentSession *)session cancelledRealSamples:(int32_t)cancelledRealSamples completedPending:(BOOL)completedPending __attribute__((swift_name("doCopy(session:cancelledRealSamples:completedPending:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t cancelledRealSamples __attribute__((swift_name("cancelledRealSamples")));
@property (readonly) BOOL completedPending __attribute__((swift_name("completedPending")));
@property (readonly) NCPDocumentSession *session __attribute__((swift_name("session")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeObject")))
@interface NCPStrokeObject : NCPBase
- (instancetype)initWithId:(NCPObjectId *)id objectRevision:(int64_t)objectRevision layerId:(NCPLayerId *)layerId brush:(NCPBrushSpec *)brush samples:(NCPStrokeSamples *)samples transform:(NCPAffine2D *)transform __attribute__((swift_name("init(id:objectRevision:layerId:brush:samples:transform:)"))) __attribute__((objc_designated_initializer));
- (NCPBoundsD *)bounds __attribute__((swift_name("bounds()")));
- (NCPStrokeObject *)doCopyId:(NCPObjectId *)id objectRevision:(int64_t)objectRevision layerId:(NCPLayerId *)layerId brush:(NCPBrushSpec *)brush samples:(NCPStrokeSamples *)samples transform:(NCPAffine2D *)transform __attribute__((swift_name("doCopy(id:objectRevision:layerId:brush:samples:transform:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@property (readonly) NCPObjectId *id __attribute__((swift_name("id")));
@property (readonly) NCPLayerId *layerId __attribute__((swift_name("layerId")));
@property (readonly) int64_t objectRevision __attribute__((swift_name("objectRevision")));
@property (readonly) NCPStrokeSamples *samples __attribute__((swift_name("samples")));
@property (readonly) NCPAffine2D *transform __attribute__((swift_name("transform")));
@end


/** Owns copies of real sample arrays; prediction and platform events have no storage slot. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeSamples")))
@interface NCPStrokeSamples : NCPBase
@property (class, readonly, getter=companion) NCPStrokeSamplesCompanion *companion __attribute__((swift_name("companion")));
- (NCPBoundsD *)boundsRadius:(double)radius __attribute__((swift_name("bounds(radius:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (float)pressureIndex:(int32_t)index __attribute__((swift_name("pressure(index:)")));
- (int64_t)timeMicrosIndex:(int32_t)index __attribute__((swift_name("timeMicros(index:)")));
- (double)xIndex:(int32_t)index __attribute__((swift_name("x(index:)")));
- (double)yIndex:(int32_t)index __attribute__((swift_name("y(index:)")));

/** Declared primitive payload plus sample header; not a measured heap allocation. */
@property (readonly) int64_t logicalByteCharge __attribute__((swift_name("logicalByteCharge")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeSamples.Companion")))
@interface NCPStrokeSamplesCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPStrokeSamplesCompanion *shared __attribute__((swift_name("shared")));
- (NCPStrokeSamples *)doCopyOfXs:(NCPKotlinDoubleArray *)xs ys:(NCPKotlinDoubleArray *)ys pressures:(NCPKotlinFloatArray *)pressures timesMicros:(NCPKotlinLongArray *)timesMicros __attribute__((swift_name("doCopyOf(xs:ys:pressures:timesMicros:)")));
@end


/** A completed stroke proposal has stable identity; the authority chooses its base on first dispatch. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeSubmission")))
@interface NCPStrokeSubmission : NCPBase
- (instancetype)initWithId:(NCPCommandId *)id objectId:(NCPObjectId *)objectId stroke:(NCPCompletedInput *)stroke __attribute__((swift_name("init(id:objectId:stroke:)"))) __attribute__((objc_designated_initializer));
- (NCPStrokeSubmission *)doCopyId:(NCPCommandId *)id objectId:(NCPObjectId *)objectId stroke:(NCPCompletedInput *)stroke __attribute__((swift_name("doCopy(id:objectId:stroke:)")));

/** A completed stroke proposal has stable identity; the authority chooses its base on first dispatch. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** A completed stroke proposal has stable identity; the authority chooses its base on first dispatch. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** A completed stroke proposal has stable identity; the authority chooses its base on first dispatch. */
- (NSString *)description __attribute__((swift_name("description()")));

/** Logical admission charge, NOT measured JVM/native heap or the document's total memory. */
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPObjectId *objectId __attribute__((swift_name("objectId")));
@property (readonly) NCPCompletedInput *stroke __attribute__((swift_name("stroke")));
@end


/** Bounded, single-owner FIFO model. No coroutines, thread safety, disk, automatic retry or rebase.
 * Confirmed payloads remain charged until collected. Recovery removes blocked work only by explicit handoff.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeSubmissionQueue")))
@interface NCPStrokeSubmissionQueue : NCPBase
- (instancetype)initWithSceneId:(NCPSceneId *)sceneId maxEntries:(int32_t)maxEntries byteBudget:(int64_t)byteBudget __attribute__((swift_name("init(sceneId:maxEntries:byteBudget:)"))) __attribute__((objc_designated_initializer));

/** Explicit recovery handoff for a definitive rejection; ambiguous/in-flight work cannot transfer. */
- (NCPKotlinPair<NCPStrokeSubmission *, NCPRejection *> * _Nullable)collectBlockedId:(NCPCommandId *)id __attribute__((swift_name("collectBlocked(id:)")));

/** Transfer accepted payload + receipt back to the host; never removes pending/failed work. */
- (NCPKotlinPair<NCPStrokeSubmission *, NCPCommitReceipt *> * _Nullable)collectConfirmedId:(NCPCommandId *)id __attribute__((swift_name("collectConfirmed(id:)")));
- (NCPCompletionUpdate *)completeAttempt:(NCPSubmissionAttempt *)attempt result:(id<NCPCommitResult>)result __attribute__((swift_name("complete(attempt:result:)")));

/** At first dispatch prepare against the authority snapshot, never the UI's DOWN revision. */
- (id<NCPDispatchResult>)dispatchScene:(NCPSceneSnapshot *)scene __attribute__((swift_name("dispatch(scene:)")));
- (id<NCPAdmissionResult>)enqueueSubmission:(NCPStrokeSubmission *)submission __attribute__((swift_name("enqueue(submission:)")));
- (BOOL)retryId:(NCPCommandId *)id __attribute__((swift_name("retry(id:)")));
- (NSArray<NCPSubmissionSummary *> *)snapshotStates __attribute__((swift_name("snapshotStates()")));
- (id<NCPSubmissionState> _Nullable)stateId:(NCPCommandId *)id __attribute__((swift_name("state(id:)")));

/** Stop new admissions, continue draining previously owned work; deliberately no discard/clear. */
- (void)stopAccepting __attribute__((swift_name("stopAccepting()")));
- (NCPStrokeSubmission * _Nullable)submissionId:(NCPCommandId *)id __attribute__((swift_name("submission(id:)")));

/** The commit outcome is unknown. Keep exact payload/patch; do not claim failure rolled it back. */
- (NCPCompletionUpdate *)transportFailedAttempt:(NCPSubmissionAttempt *)attempt detail:(NSString *)detail __attribute__((swift_name("transportFailed(attempt:detail:)")));
@property (readonly) int64_t byteBudget __attribute__((swift_name("byteBudget")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) int32_t maxEntries __attribute__((swift_name("maxEntries")));
@property (readonly) int32_t retainedEntries __attribute__((swift_name("retainedEntries")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@property (readonly) int32_t unconfirmedEntries __attribute__((swift_name("unconfirmedEntries")));
@end


/** Capture at stroke admission, never look up the current layer/tool during IO completion. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeTarget")))
@interface NCPStrokeTarget : NCPBase
- (instancetype)initWithSceneId:(NCPSceneId *)sceneId layerId:(NCPLayerId *)layerId brush:(NCPBrushSpec *)brush __attribute__((swift_name("init(sceneId:layerId:brush:)"))) __attribute__((objc_designated_initializer));
- (NCPStrokeTarget *)doCopySceneId:(NCPSceneId *)sceneId layerId:(NCPLayerId *)layerId brush:(NCPBrushSpec *)brush __attribute__((swift_name("doCopy(sceneId:layerId:brush:)")));

/** Capture at stroke admission, never look up the current layer/tool during IO completion. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Capture at stroke admission, never look up the current layer/tool during IO completion. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Capture at stroke admission, never look up the current layer/tool during IO completion. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@property (readonly) NCPLayerId *layerId __attribute__((swift_name("layerId")));
@property (readonly) NCPSceneId *sceneId __attribute__((swift_name("sceneId")));
@end


/** Opaque per-dispatch capability. A completion from an older attempt or another queue is rejected. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionAttempt")))
@interface NCPSubmissionAttempt : NCPBase
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) int64_t number __attribute__((swift_name("number")));
@property (readonly) NCPScenePatch *patch __attribute__((swift_name("patch")));
@end

__attribute__((swift_name("SubmissionState")))
@protocol NCPSubmissionState
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionStateBlocked")))
@interface NCPSubmissionStateBlocked : NCPBase <NCPSubmissionState>
- (instancetype)initWithReason:(NCPRejection *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPSubmissionStateBlocked *)doCopyReason:(NCPRejection *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPRejection *reason __attribute__((swift_name("reason")));
@end


/** Authority acceptance. The current receipt is MEMORY_ONLY, not a saved-to-disk indicator. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionStateConfirmed")))
@interface NCPSubmissionStateConfirmed : NCPBase <NCPSubmissionState>
- (instancetype)initWithReceipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("init(receipt:)"))) __attribute__((objc_designated_initializer));
- (NCPSubmissionStateConfirmed *)doCopyReceipt:(NCPCommitReceipt *)receipt __attribute__((swift_name("doCopy(receipt:)")));

/** Authority acceptance. The current receipt is MEMORY_ONLY, not a saved-to-disk indicator. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Authority acceptance. The current receipt is MEMORY_ONLY, not a saved-to-disk indicator. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Authority acceptance. The current receipt is MEMORY_ONLY, not a saved-to-disk indicator. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPCommitReceipt *receipt __attribute__((swift_name("receipt")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionStateInFlight")))
@interface NCPSubmissionStateInFlight : NCPBase <NCPSubmissionState>
- (instancetype)initWithAttemptNumber:(int64_t)attemptNumber __attribute__((swift_name("init(attemptNumber:)"))) __attribute__((objc_designated_initializer));
- (NCPSubmissionStateInFlight *)doCopyAttemptNumber:(int64_t)attemptNumber __attribute__((swift_name("doCopy(attemptNumber:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t attemptNumber __attribute__((swift_name("attemptNumber")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionStateQueued")))
@interface NCPSubmissionStateQueued : NCPBase <NCPSubmissionState>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)queued __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPSubmissionStateQueued *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionStateRetryRequired")))
@interface NCPSubmissionStateRetryRequired : NCPBase <NCPSubmissionState>
- (instancetype)initWithDetail:(NSString *)detail __attribute__((swift_name("init(detail:)"))) __attribute__((objc_designated_initializer));
- (NCPSubmissionStateRetryRequired *)doCopyDetail:(NSString *)detail __attribute__((swift_name("doCopy(detail:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *detail __attribute__((swift_name("detail")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("SubmissionSummary")))
@interface NCPSubmissionSummary : NCPBase
- (instancetype)initWithId:(NCPCommandId *)id state:(id<NCPSubmissionState>)state chargedBytes:(int64_t)chargedBytes __attribute__((swift_name("init(id:state:chargedBytes:)"))) __attribute__((objc_designated_initializer));
- (NCPSubmissionSummary *)doCopyId:(NCPCommandId *)id state:(id<NCPSubmissionState>)state chargedBytes:(int64_t)chargedBytes __attribute__((swift_name("doCopy(id:state:chargedBytes:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t chargedBytes __attribute__((swift_name("chargedBytes")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) id<NCPSubmissionState> state __attribute__((swift_name("state")));
@end


/**
 * Single-owner, resident-byte reference policy. No platform buffers or resource disposal.
 * Displacement is informational, NOT permission to free a value still leased by a reader.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WeightedLruCache")))
@interface NCPWeightedLruCache<K, V> : NCPBase
- (instancetype)initWithMaxBytes:(int64_t)maxBytes weigh:(NCPLong *(^)(V))weigh __attribute__((swift_name("init(maxBytes:weigh:)"))) __attribute__((objc_designated_initializer));
- (NSArray<NCPDisplacedEntry<K, V> *> *)clear __attribute__((swift_name("clear()")));
- (V _Nullable)getKey:(K)key __attribute__((swift_name("get(key:)")));
- (id<NCPCacheAdmission>)putKey:(K)key value:(V)value __attribute__((swift_name("put(key:value:)")));
@property (readonly) int64_t maxBytes __attribute__((swift_name("maxBytes")));
@property (readonly) int64_t residentBytes __attribute__((swift_name("residentBytes")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((swift_name("WorkAdmission")))
@protocol NCPWorkAdmission
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkAdmissionAccepted")))
@interface NCPWorkAdmissionAccepted : NCPBase <NCPWorkAdmission>
- (instancetype)initWithTicket:(NCPOwnerWorkTicket *)ticket __attribute__((swift_name("init(ticket:)"))) __attribute__((objc_designated_initializer));
- (NCPWorkAdmissionAccepted *)doCopyTicket:(NCPOwnerWorkTicket *)ticket __attribute__((swift_name("doCopy(ticket:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPOwnerWorkTicket *ticket __attribute__((swift_name("ticket")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkAdmissionRejected")))
@interface NCPWorkAdmissionRejected : NCPBase <NCPWorkAdmission>
- (instancetype)initWithReason:(NCPWorkAdmissionFailure *)reason __attribute__((swift_name("init(reason:)"))) __attribute__((objc_designated_initializer));
- (NCPWorkAdmissionRejected *)doCopyReason:(NCPWorkAdmissionFailure *)reason __attribute__((swift_name("doCopy(reason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPWorkAdmissionFailure *reason __attribute__((swift_name("reason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkAdmissionFailure")))
@interface NCPWorkAdmissionFailure : NCPKotlinEnum<NCPWorkAdmissionFailure *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPWorkAdmissionFailure *stopped __attribute__((swift_name("stopped")));
@property (class, readonly) NCPWorkAdmissionFailure *entryCapacity __attribute__((swift_name("entryCapacity")));
@property (class, readonly) NCPWorkAdmissionFailure *byteCapacity __attribute__((swift_name("byteCapacity")));
@property (class, readonly) NCPWorkAdmissionFailure *invalidCharge __attribute__((swift_name("invalidCharge")));
@property (class, readonly) NCPWorkAdmissionFailure *idExhausted __attribute__((swift_name("idExhausted")));
@property (class, readonly) NCPWorkAdmissionFailure *workerTerminated __attribute__((swift_name("workerTerminated")));
+ (NCPKotlinArray<NCPWorkAdmissionFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPWorkAdmissionFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((swift_name("WorkResult")))
@protocol NCPWorkResult
@required
@end


/** Worker terminated before this queued job started; compute was never invoked. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkResultNotExecuted")))
@interface NCPWorkResultNotExecuted : NCPBase <NCPWorkResult>
- (instancetype)initWithDetail:(NSString *)detail __attribute__((swift_name("init(detail:)"))) __attribute__((objc_designated_initializer));
- (NCPWorkResultNotExecuted *)doCopyDetail:(NSString *)detail __attribute__((swift_name("doCopy(detail:)")));

/** Worker terminated before this queued job started; compute was never invoked. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Worker terminated before this queued job started; compute was never invoked. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Worker terminated before this queued job started; compute was never invoked. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *detail __attribute__((swift_name("detail")));
@end


/** Work may have applied a side effect before throwing. Never implies rollback or safe re-execution. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkResultOutcomeUnknown")))
@interface NCPWorkResultOutcomeUnknown : NCPBase <NCPWorkResult>
- (instancetype)initWithExceptionType:(NSString *)exceptionType detail:(NSString *)detail __attribute__((swift_name("init(exceptionType:detail:)"))) __attribute__((objc_designated_initializer));
- (NCPWorkResultOutcomeUnknown *)doCopyExceptionType:(NSString *)exceptionType detail:(NSString *)detail __attribute__((swift_name("doCopy(exceptionType:detail:)")));

/** Work may have applied a side effect before throwing. Never implies rollback or safe re-execution. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Work may have applied a side effect before throwing. Never implies rollback or safe re-execution. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Work may have applied a side effect before throwing. Never implies rollback or safe re-execution. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *detail __attribute__((swift_name("detail")));
@property (readonly) NSString *exceptionType __attribute__((swift_name("exceptionType")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("WorkResultSucceeded")))
@interface NCPWorkResultSucceeded<R> : NCPBase <NCPWorkResult>
- (instancetype)initWithValue:(R)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPWorkResultSucceeded<R> *)doCopyValue:(R)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) R value __attribute__((swift_name("value")));
@end


/** Unfrozen local-process native scalar buffers, not a persisted wire format or another session.
 * NSData must stay immutable during this synchronous owner call. No platform pointer is retained.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("ApplePreviewInput")))
@interface NCPApplePreviewInput : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/** Unfrozen local-process native scalar buffers, not a persisted wire format or another session.
 * NSData must stay immutable during this synchronous owner call. No platform pointer is retained.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)applePreviewInput __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPApplePreviewInput *shared __attribute__((swift_name("shared")));
- (NCPPreviewInputResult *)offerNotebook:(NCPPagedPreviewNotebook *)notebook phase:(NCPPointerPhase *)phase eventTimeMicros:(int64_t)eventTimeMicros xs:(NSData *)xs ys:(NSData *)ys pressures:(NSData *)pressures times:(NSData *)times __attribute__((swift_name("offer(notebook:phase:eventTimeMicros:xs:ys:pressures:times:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentAuthorityStatus")))
@interface NCPDocumentAuthorityStatus : NCPBase
- (instancetype)initWithLiveByteCharge:(int64_t)liveByteCharge journalByteCharge:(int64_t)journalByteCharge identityByteCharge:(int64_t)identityByteCharge operationCount:(int32_t)operationCount pageCount:(int32_t)pageCount objectCount:(int32_t)objectCount undoCount:(int32_t)undoCount redoCount:(int32_t)redoCount historyByteCharge:(int64_t)historyByteCharge nextUndoOperationId:(NCPCommandId * _Nullable)nextUndoOperationId nextRedoOperationId:(NCPCommandId * _Nullable)nextRedoOperationId evictedHistoryCount:(int64_t)evictedHistoryCount __attribute__((swift_name("init(liveByteCharge:journalByteCharge:identityByteCharge:operationCount:pageCount:objectCount:undoCount:redoCount:historyByteCharge:nextUndoOperationId:nextRedoOperationId:evictedHistoryCount:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentAuthorityStatus *)doCopyLiveByteCharge:(int64_t)liveByteCharge journalByteCharge:(int64_t)journalByteCharge identityByteCharge:(int64_t)identityByteCharge operationCount:(int32_t)operationCount pageCount:(int32_t)pageCount objectCount:(int32_t)objectCount undoCount:(int32_t)undoCount redoCount:(int32_t)redoCount historyByteCharge:(int64_t)historyByteCharge nextUndoOperationId:(NCPCommandId * _Nullable)nextUndoOperationId nextRedoOperationId:(NCPCommandId * _Nullable)nextRedoOperationId evictedHistoryCount:(int64_t)evictedHistoryCount __attribute__((swift_name("doCopy(liveByteCharge:journalByteCharge:identityByteCharge:operationCount:pageCount:objectCount:undoCount:redoCount:historyByteCharge:nextUndoOperationId:nextRedoOperationId:evictedHistoryCount:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t evictedHistoryCount __attribute__((swift_name("evictedHistoryCount")));
@property (readonly) int64_t historyByteCharge __attribute__((swift_name("historyByteCharge")));
@property (readonly) int64_t identityByteCharge __attribute__((swift_name("identityByteCharge")));
@property (readonly) int64_t journalByteCharge __attribute__((swift_name("journalByteCharge")));
@property (readonly) int64_t liveByteCharge __attribute__((swift_name("liveByteCharge")));
@property (readonly) NCPCommandId * _Nullable nextRedoOperationId __attribute__((swift_name("nextRedoOperationId")));
@property (readonly) NCPCommandId * _Nullable nextUndoOperationId __attribute__((swift_name("nextUndoOperationId")));
@property (readonly) int32_t objectCount __attribute__((swift_name("objectCount")));
@property (readonly) int32_t operationCount __attribute__((swift_name("operationCount")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int32_t redoCount __attribute__((swift_name("redoCount")));
@property (readonly) int32_t undoCount __attribute__((swift_name("undoCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudget")))
@interface NCPDocumentBudget : NCPBase
- (instancetype)initWithMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes __attribute__((swift_name("init(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPDocumentBudgetCompanion *companion __attribute__((swift_name("companion")));
- (NCPDocumentBudget *)doCopyMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes __attribute__((swift_name("doCopy(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t historyBytes __attribute__((swift_name("historyBytes")));
@property (readonly) int32_t historyEntries __attribute__((swift_name("historyEntries")));
@property (readonly) int64_t identityBytes __attribute__((swift_name("identityBytes")));

/** Per-notebook active input reservation; independent of retained document/history quotas. */
@property (readonly) int64_t inputBytes __attribute__((swift_name("inputBytes")));
@property (readonly) int64_t journalBytes __attribute__((swift_name("journalBytes")));
@property (readonly) int32_t journalEntries __attribute__((swift_name("journalEntries")));
@property (readonly) int64_t liveBytes __attribute__((swift_name("liveBytes")));
@property (readonly) int32_t maxObjects __attribute__((swift_name("maxObjects")));
@property (readonly) int32_t maxPages __attribute__((swift_name("maxPages")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentBudget.Companion")))
@interface NCPDocumentBudgetCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPDocumentBudgetCompanion *shared __attribute__((swift_name("shared")));

/** Raw immutable values only: no owner, authority, reservation, clamping or exception recovery.
 * A valid general budget may still be unsupported or insufficient for a preview notebook.
 */
- (NCPDocumentBudgetCreation *)validatedMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes __attribute__((swift_name("validated(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)")));
@end


/** Exactly one of budget/failure is non-null. Does not create a document or promise physical heap. */
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
+ (NCPKotlinArray<NCPDocumentBudgetFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPDocumentBudgetFailure *> *entries __attribute__((swift_name("entries")));
@end


/** Unfrozen root operations. One document identity/base revision and one operation-id namespace. */
__attribute__((swift_name("DocumentCommand")))
@protocol NCPDocumentCommand
@required
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandCanvas")))
@interface NCPDocumentCommandCanvas : NCPBase <NCPDocumentCommand>
- (instancetype)initWithPageId:(NCPPageId *)pageId command:(id<NCPCanvasCommand>)command __attribute__((swift_name("init(pageId:command:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandCanvas *)doCopyPageId:(NCPPageId *)pageId command:(id<NCPCanvasCommand>)command __attribute__((swift_name("doCopy(pageId:command:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) id<NCPCanvasCommand> command __attribute__((swift_name("command")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageId *pageId __attribute__((swift_name("pageId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandCopyPage")))
@interface NCPDocumentCommandCopyPage : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision source:(NCPPageId *)source expectedPageRevision:(int64_t)expectedPageRevision newPageId:(NCPPageId *)newPageId newLayerId:(NCPLayerId *)newLayerId objectIds:(NSDictionary<NCPObjectId *, NCPObjectId *> *)objectIds before:(NCPPageId * _Nullable)before __attribute__((swift_name("init(id:documentId:baseRevision:source:expectedPageRevision:newPageId:newLayerId:objectIds:before:)"))) __attribute__((objc_designated_initializer));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSDictionary<NCPObjectId *, NCPObjectId *> *)objectIds __attribute__((swift_name("objectIds()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPPageId * _Nullable before __attribute__((swift_name("before")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) int64_t expectedPageRevision __attribute__((swift_name("expectedPageRevision")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly, getter=doNewLayerId) NCPLayerId *newLayerId __attribute__((swift_name("newLayerId")));
@property (readonly, getter=doNewPageId) NCPPageId *newPageId __attribute__((swift_name("newPageId")));
@property (readonly) int32_t objectMappingCount __attribute__((swift_name("objectMappingCount")));
@property (readonly) NCPPageId *source __attribute__((swift_name("source")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandDeletePage")))
@interface NCPDocumentCommandDeletePage : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId __attribute__((swift_name("init(id:documentId:baseRevision:pageId:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandDeletePage *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId __attribute__((swift_name("doCopy(id:documentId:baseRevision:pageId:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageId *pageId __attribute__((swift_name("pageId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandInsertPage")))
@interface NCPDocumentCommandInsertPage : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision page:(NCPPageDescriptor *)page before:(NCPPageId * _Nullable)before __attribute__((swift_name("init(id:documentId:baseRevision:page:before:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandInsertPage *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision page:(NCPPageDescriptor *)page before:(NCPPageId * _Nullable)before __attribute__((swift_name("doCopy(id:documentId:baseRevision:page:before:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPPageId * _Nullable before __attribute__((swift_name("before")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageDescriptor *page __attribute__((swift_name("page")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandMovePage")))
@interface NCPDocumentCommandMovePage : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId before:(NCPPageId * _Nullable)before __attribute__((swift_name("init(id:documentId:baseRevision:pageId:before:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandMovePage *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId before:(NCPPageId * _Nullable)before __attribute__((swift_name("doCopy(id:documentId:baseRevision:pageId:before:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPPageId * _Nullable before __attribute__((swift_name("before")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageId *pageId __attribute__((swift_name("pageId")));
@end


/** Prepared canvas-session patch keeps its original base and full payload through transport retries. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandPreparedCanvas")))
@interface NCPDocumentCommandPreparedCanvas : NCPBase <NCPDocumentCommand>
- (instancetype)initWithPageId:(NCPPageId *)pageId patch:(NCPScenePatch *)patch __attribute__((swift_name("init(pageId:patch:)"))) __attribute__((objc_designated_initializer));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageId *pageId __attribute__((swift_name("pageId")));
@property (readonly) NCPScenePatch *patch __attribute__((swift_name("patch")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandRedo")))
@interface NCPDocumentCommandRedo : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision expectedOperationId:(NCPCommandId *)expectedOperationId __attribute__((swift_name("init(id:documentId:baseRevision:expectedOperationId:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandRedo *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision expectedOperationId:(NCPCommandId *)expectedOperationId __attribute__((swift_name("doCopy(id:documentId:baseRevision:expectedOperationId:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *expectedOperationId __attribute__((swift_name("expectedOperationId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandRename")))
@interface NCPDocumentCommandRename : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision title:(NSString *)title __attribute__((swift_name("init(id:documentId:baseRevision:title:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandRename *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision title:(NSString *)title __attribute__((swift_name("doCopy(id:documentId:baseRevision:title:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandSetPaper")))
@interface NCPDocumentCommandSetPaper : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId width:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("init(id:documentId:baseRevision:pageId:width:height:paper:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandSetPaper *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision pageId:(NCPPageId *)pageId width:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("doCopy(id:documentId:baseRevision:pageId:width:height:paper:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) double height __attribute__((swift_name("height")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@property (readonly) NCPPageId *pageId __attribute__((swift_name("pageId")));
@property (readonly) NCPPaperKind *paper __attribute__((swift_name("paper")));
@property (readonly) double width __attribute__((swift_name("width")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommandUndo")))
@interface NCPDocumentCommandUndo : NCPBase <NCPDocumentCommand>
- (instancetype)initWithId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision expectedOperationId:(NCPCommandId *)expectedOperationId __attribute__((swift_name("init(id:documentId:baseRevision:expectedOperationId:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommandUndo *)doCopyId:(NCPCommandId *)id documentId:(NCPSceneId *)documentId baseRevision:(int64_t)baseRevision expectedOperationId:(NCPCommandId *)expectedOperationId __attribute__((swift_name("doCopy(id:documentId:baseRevision:expectedOperationId:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int64_t baseRevision __attribute__((swift_name("baseRevision")));
@property (readonly) NCPSceneId *documentId __attribute__((swift_name("documentId")));
@property (readonly) NCPCommandId *expectedOperationId __attribute__((swift_name("expectedOperationId")));
@property (readonly) NCPCommandId *id __attribute__((swift_name("id")));
@end

__attribute__((swift_name("DocumentCommit")))
@protocol NCPDocumentCommit
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommitAccepted")))
@interface NCPDocumentCommitAccepted : NCPBase <NCPDocumentCommit>
- (instancetype)initWithReceipt:(NCPDocumentReceipt *)receipt __attribute__((swift_name("init(receipt:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommitAccepted *)doCopyReceipt:(NCPDocumentReceipt *)receipt __attribute__((swift_name("doCopy(receipt:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPDocumentReceipt *receipt __attribute__((swift_name("receipt")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentCommitRejected")))
@interface NCPDocumentCommitRejected : NCPBase <NCPDocumentCommit>
- (instancetype)initWithReason:(NCPDocumentRejection *)reason sceneReason:(NCPRejection * _Nullable)sceneReason __attribute__((swift_name("init(reason:sceneReason:)"))) __attribute__((objc_designated_initializer));
- (NCPDocumentCommitRejected *)doCopyReason:(NCPDocumentRejection *)reason sceneReason:(NCPRejection * _Nullable)sceneReason __attribute__((swift_name("doCopy(reason:sceneReason:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPDocumentRejection *reason __attribute__((swift_name("reason")));
@property (readonly) NCPRejection * _Nullable sceneReason __attribute__((swift_name("sceneReason")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentReceipt")))
@interface NCPDocumentReceipt : NCPBase
- (NSSet<NCPPageId *> *)affectedPages __attribute__((swift_name("affectedPages()")));
@property (readonly) NCPDurability *durability __attribute__((swift_name("durability")));
@property (readonly) NCPCommandId *operationId __attribute__((swift_name("operationId")));
@property (readonly) int64_t rootRevision __attribute__((swift_name("rootRevision")));
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
+ (NCPKotlinArray<NCPDocumentRejection *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPDocumentRejection *> *entries __attribute__((swift_name("entries")));
@end


/** One canonical scene plus immutable page membership. Root revision is the scene's sole commit sequence. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentRoot")))
@interface NCPDocumentRoot : NCPBase
@property (class, readonly, getter=companion) NCPDocumentRootCompanion *companion __attribute__((swift_name("companion")));
- (NCPPageDescriptor * _Nullable)findPageId:(NCPPageId *)id __attribute__((swift_name("findPage(id:)")));
- (NCPPageDescriptor *)pageIndex:(int32_t)index __attribute__((swift_name("page(index:)")));
- (NSArray<NCPStrokeObject *> *)pageObjectsId:(NCPPageId *)id __attribute__((swift_name("pageObjects(id:)")));
- (NSArray<NCPPageDescriptor *> *)pages __attribute__((swift_name("pages()")));
@property (readonly) NCPSceneId *id __attribute__((swift_name("id")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int64_t revision __attribute__((swift_name("revision")));
@property (readonly) NCPSceneSnapshot *scene __attribute__((swift_name("scene")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("DocumentRoot.Companion")))
@interface NCPDocumentRootCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPDocumentRootCompanion *shared __attribute__((swift_name("shared")));

/** Restore/compose immutable canonical values; caller must create only one mutable authority. */
- (NCPDocumentRoot *)composeTitle:(NSString *)title scene:(NCPSceneSnapshot *)scene pages:(NSArray<NCPPageDescriptor *> *)pages __attribute__((swift_name("compose(title:scene:pages:)")));
- (NCPDocumentRoot *)emptyId:(NCPSceneId *)id title:(NSString *)title firstPage:(NCPPageDescriptor *)firstPage __attribute__((swift_name("empty(id:title:firstPage:)")));
@end


/** C0 single-owner reference. Page directory and canonical scene publish together, with one root revision.
 * Receipts are MEMORY_ONLY. Full-map copies / identity tombstones / bounded journal are not production storage.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("MemoryDocumentAuthority")))
@interface NCPMemoryDocumentAuthority : NCPBase

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (instancetype _Nullable)initWithInitial:(NCPDocumentRoot *)initial budget:(NCPDocumentBudget *)budget error:(NSError * _Nullable * _Nullable)error ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("init(initial:budget:ownerCheck:)"))) __attribute__((objc_designated_initializer));
- (id<NCPDocumentCommit>)commitCommand:(id<NCPDocumentCommand>)command __attribute__((swift_name("commit(command:)")));
- (NCPDocumentRoot *)snapshot __attribute__((swift_name("snapshot()")));
- (NCPDocumentAuthorityStatus *)status __attribute__((swift_name("status()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PageDescriptor")))
@interface NCPPageDescriptor : NCPBase
- (instancetype)initWithId:(NCPPageId *)id layerId:(NCPLayerId *)layerId width:(double)width height:(double)height paper:(NCPPaperKind *)paper contentRevision:(int64_t)contentRevision __attribute__((swift_name("init(id:layerId:width:height:paper:contentRevision:)"))) __attribute__((objc_designated_initializer));
- (NCPPageDescriptor *)doCopyId:(NCPPageId *)id layerId:(NCPLayerId *)layerId width:(double)width height:(double)height paper:(NCPPaperKind *)paper contentRevision:(int64_t)contentRevision __attribute__((swift_name("doCopy(id:layerId:width:height:paper:contentRevision:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
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
- (instancetype)initWithValue:(NSString *)value __attribute__((swift_name("init(value:)"))) __attribute__((objc_designated_initializer));
- (NCPPageId *)doCopyValue:(NSString *)value __attribute__((swift_name("doCopy(value:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *value __attribute__((swift_name("value")));
@end


/** Unfrozen bounded preview interchange. No filesystem, mutable authority, history or durable receipts. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewCodec")))
@interface NCPPagedPreviewCodec : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/** Unfrozen bounded preview interchange. No filesystem, mutable authority, history or durable receipts. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)pagedPreviewCodec __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPagedPreviewCodec *shared __attribute__((swift_name("shared")));

/** Already decoded immutable input avoids a second full parse/point-array allocation during native import.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPDocumentRoot * _Nullable)doCopyAsNewSource:(NCPDocumentRoot *)source documentId:(NCPSceneId *)documentId pageIds:(NSDictionary<NCPPageId *, NCPPageId *> *)pageIds layerIds:(NSDictionary<NCPLayerId *, NCPLayerId *> *)layerIds objectIds:(NSDictionary<NCPObjectId *, NCPObjectId *> *)objectIds error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("doCopyAsNew(source:documentId:pageIds:layerIds:objectIds:)")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPDocumentRoot * _Nullable)decodeText:(NSString *)text error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("decode(text:)")));

/** Reserve input text / UTF conversion plus worst-case owned sample arrays before worker decoding.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (int64_t)decodingWorkByteChargeCharacterCount:(int32_t)characterCount error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("decodingWorkByteCharge(characterCount:)"))) __attribute__((swift_error(nonnull_error)));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NSString * _Nullable)encodeRoot:(NCPDocumentRoot *)root error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encode(root:)")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (int64_t)encodingWorkByteChargeRoot:(NCPDocumentRoot *)root error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encodingWorkByteCharge(root:)"))) __attribute__((swift_error(nonnull_error)));

/** Bounded metadata only. Callers must decode the whole document before trusting its body.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPPagedPreviewHeader * _Nullable)headerText:(NSString *)text error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("header(text:)")));

/** Caller supplies all new identities; no allocator or mutable authority is hidden in a codec.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPDocumentRoot * _Nullable)importAsCopyText:(NSString *)text documentId:(NCPSceneId *)documentId pageIds:(NSDictionary<NCPPageId *, NCPPageId *> *)pageIds layerIds:(NSDictionary<NCPLayerId *, NCPLayerId *> *)layerIds objectIds:(NSDictionary<NCPObjectId *, NCPObjectId *> *)objectIds error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("importAsCopy(text:documentId:pageIds:layerIds:objectIds:)")));

/** Unfrozen primitive accessors for native hosts; one shared limit, no mirrored magic numbers. */
- (int32_t)maximumCharacterCount __attribute__((swift_name("maximumCharacterCount()")));
- (int64_t)maximumDecodingWorkByteCharge __attribute__((swift_name("maximumDecodingWorkByteCharge()")));
- (int32_t)maximumUtf8ByteCount __attribute__((swift_name("maximumUtf8ByteCount()")));

/** Conservative retained charge; validates encode/decode capacity and known preview algorithms.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (int64_t)retainedByteChargeRoot:(NCPDocumentRoot *)root error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("retainedByteCharge(root:)"))) __attribute__((swift_error(nonnull_error)));
@property (readonly) int32_t MAX_CHARACTERS __attribute__((swift_name("MAX_CHARACTERS")));
@property (readonly) int32_t MAX_OBJECTS __attribute__((swift_name("MAX_OBJECTS")));
@property (readonly) int32_t MAX_PAGES __attribute__((swift_name("MAX_PAGES")));
@property (readonly) int32_t MAX_POINTS __attribute__((swift_name("MAX_POINTS")));
@property (readonly) int32_t MAX_STROKE_POINTS __attribute__((swift_name("MAX_STROKE_POINTS")));
@property (readonly) int32_t MAX_UTF8_BYTES __attribute__((swift_name("MAX_UTF8_BYTES")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewHeader")))
@interface NCPPagedPreviewHeader : NCPBase
- (instancetype)initWithDocumentId:(NSString *)documentId title:(NSString *)title pageCount:(int32_t)pageCount objectCount:(int32_t)objectCount revision:(int64_t)revision formatVersion:(int32_t)formatVersion __attribute__((swift_name("init(documentId:title:pageCount:objectCount:revision:formatVersion:)"))) __attribute__((objc_designated_initializer));
- (NCPPagedPreviewHeader *)doCopyDocumentId:(NSString *)documentId title:(NSString *)title pageCount:(int32_t)pageCount objectCount:(int32_t)objectCount revision:(int64_t)revision formatVersion:(int32_t)formatVersion __attribute__((swift_name("doCopy(documentId:title:pageCount:objectCount:revision:formatVersion:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *documentId __attribute__((swift_name("documentId")));
@property (readonly) int32_t formatVersion __attribute__((swift_name("formatVersion")));
@property (readonly) int32_t objectCount __attribute__((swift_name("objectCount")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int64_t revision __attribute__((swift_name("revision")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@end


/** UI-owner preview session. Exactly one authority; selected-page projection is a read-only cache. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewNotebook")))
@interface NCPPagedPreviewNotebook : NCPBase

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (instancetype _Nullable)initWithInitial:(NCPDocumentRoot *)initial sessionNonce:(NSString *)sessionNonce budget:(NCPDocumentBudget *)budget error:(NSError * _Nullable * _Nullable)error ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("init(initial:sessionNonce:budget:ownerCheck:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPPagedPreviewNotebookCompanion *companion __attribute__((swift_name("companion")));
- (BOOL)appendStrokeX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("appendStroke(x:y:pressure:timeMicros:)")));
- (NCPDocumentAuthorityStatus *)authorityStatus __attribute__((swift_name("authorityStatus()")));
- (BOOL)beginStrokeX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("beginStroke(x:y:pressure:timeMicros:)")));
- (void)cancelStroke __attribute__((swift_name("cancelStroke()")));

/** Committed content only. Capturing does not transfer responsibility for any pending completed stroke. */
- (NCPPagedPreviewSnapshot *)capture __attribute__((swift_name("capture()")));

/** Complete accepted input, including an uncommitted completion. Active input is never a checkpoint. */
- (NCPPagedPreviewSnapshot *)captureRecovery __attribute__((swift_name("captureRecovery()")));
- (BOOL)doCopyCurrentPage __attribute__((swift_name("doCopyCurrentPage()")));
- (BOOL)deleteCurrentPage __attribute__((swift_name("deleteCurrentPage()")));

/** All request payloads are raw values. Validation precedes cancellation or identity allocation. */
- (NCPPreviewEditResult *)editRequest:(id<NCPPreviewEdit>)request __attribute__((swift_name("edit(request:)")));
- (BOOL)endStrokeX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("endStroke(x:y:pressure:timeMicros:)")));
- (BOOL)insertPage __attribute__((swift_name("insertPage()")));
- (BOOL)moveCurrentPageBefore:(NCPPageId * _Nullable)before __attribute__((swift_name("moveCurrentPage(before:)")));
- (NCPPageDescriptor *)pageIndex:(int32_t)index __attribute__((swift_name("page(index:)")));

/** Read-only display of completed ink on the selected page. Shares immutable sample/brush values. */
- (NCPPreviewPendingInk * _Nullable)pendingInk __attribute__((swift_name("pendingInk()")));

/** Immutable completed command remains here after capacity failure; cancel/selection cannot discard it. */
- (NCPDocumentCommandCanvas * _Nullable)pendingStroke __attribute__((swift_name("pendingStroke()")));
- (int32_t)pointCountIndex:(int32_t)index __attribute__((swift_name("pointCount(index:)")));
- (float)pointPressureIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointPressure(index:point:)")));
- (double)pointXIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointX(index:point:)")));
- (double)pointYIndex:(int32_t)index point:(int32_t)point __attribute__((swift_name("pointY(index:point:)")));

/** Copy-before-admission guard for synchronous platform producers. Null permits preparation only:
 * no samples or identities are accepted, and offerRealBatch repeats the checks after copying.
 * Stay on the same owner; this is not a reservation token or a content receipt.
 * No platform event is retained, and intervening edits can invalidate this observation.
 */
- (NCPPreviewInputResult * _Nullable)prepareRealBatchPhase:(NCPPointerPhase *)phase count:(int32_t)count __attribute__((swift_name("prepareRealBatch(phase:count:)")));
- (BOOL)redo __attribute__((swift_name("redo()")));
- (BOOL)renameValue:(NSString *)value __attribute__((swift_name("rename(value:)")));

/** Host calls only after immutable snapshot admission. Pending completed ink prevents retirement. */
- (BOOL)retire __attribute__((swift_name("retire()")));

/** Host may transfer responsibility only after this exact checkpoint's atomic IO receipt.
 * Retain the immutable command here too; the retired session can never submit it again.
 */
- (BOOL)retireAfterRecoverySnapshot:(NCPPagedPreviewSnapshot *)snapshot __attribute__((swift_name("retireAfterRecovery(snapshot:)")));
- (BOOL)retryPendingStroke __attribute__((swift_name("retryPendingStroke()")));
- (BOOL)selectPageId:(NCPPageId *)id __attribute__((swift_name("selectPage(id:)")));
- (BOOL)selectPenArgb:(int32_t)argb width:(double)width __attribute__((swift_name("selectPen(argb:width:)")));
- (BOOL)setPaperWidth:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("setPaper(width:height:paper:)")));

/** Mode changes never cancel accepted input or resolve a retained completion implicitly. */
- (BOOL)setReadOnlyValue:(BOOL)value __attribute__((swift_name("setReadOnly(value:)")));
- (NCPDocumentRoot *)snapshot __attribute__((swift_name("snapshot()")));
- (NCPStrokeObject *)strokeIndex:(int32_t)index __attribute__((swift_name("stroke(index:)")));
- (int32_t)strokeColorIndex:(int32_t)index __attribute__((swift_name("strokeColor(index:)")));
- (NSString *)strokeIdIndex:(int32_t)index __attribute__((swift_name("strokeId(index:)")));
- (double)strokeWidthIndex:(int32_t)index __attribute__((swift_name("strokeWidth(index:)")));
- (BOOL)undo __attribute__((swift_name("undo()")));

/** Original accepted DOWN identity; a surface must match it before MOVE/UP/cancellation.
 * Null after cancellation/completion. This is not a content revision or a new authority.
 */
@property (readonly) NSString * _Nullable activeInputOperationId __attribute__((swift_name("activeInputOperationId")));
@property (readonly) BOOL canRedo __attribute__((swift_name("canRedo")));
@property (readonly) BOOL canUndo __attribute__((swift_name("canUndo")));
@property (readonly) int64_t changeVersion __attribute__((swift_name("changeVersion")));
@property (readonly) NCPPageDescriptor *currentPage __attribute__((swift_name("currentPage")));
@property (readonly) NCPPageId *currentPageId __attribute__((swift_name("currentPageId")));
@property (readonly) int32_t currentPageIndex __attribute__((swift_name("currentPageIndex")));
@property (readonly) NSString *documentId __attribute__((swift_name("documentId")));
@property (readonly) BOOL hasActiveStroke __attribute__((swift_name("hasActiveStroke")));
@property (readonly) BOOL hasPendingStroke __attribute__((swift_name("hasPendingStroke")));
@property (readonly) int32_t inputBatchLimit __attribute__((swift_name("inputBatchLimit")));

/** Input ownership only; document/history/save/render budgets remain separate. */
@property (readonly) int64_t inputReservedByteCharge __attribute__((swift_name("inputReservedByteCharge")));
@property (readonly) BOOL isReadOnly __attribute__((swift_name("isReadOnly")));
@property (readonly) NSString *lastError __attribute__((swift_name("lastError")));
@property (readonly) NCPPreviewSessionFailure * _Nullable lastFailure __attribute__((swift_name("lastFailure")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));
@property (readonly) int32_t strokeCount __attribute__((swift_name("strokeCount")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@property (readonly) int32_t totalStrokeCount __attribute__((swift_name("totalStrokeCount")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewNotebook.Companion")))
@interface NCPPagedPreviewNotebookCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPagedPreviewNotebookCompanion *shared __attribute__((swift_name("shared")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPPagedPreviewNotebook * _Nullable)createDocumentId:(NSString *)documentId title:(NSString *)title sessionNonce:(NSString *)sessionNonce error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("create(documentId:title:sessionNonce:)")));

/** Raw creation parameters are validated before canonical value constructors or mutable authority.
 * Budget/paper are already-valid values. Owner/programmer/allocator failures are never caught.
 */
- (NCPPreviewNotebookOpen *)createValidatedDocumentId:(NSString *)documentId title:(NSString *)title sessionNonce:(NSString *)sessionNonce width:(double)width height:(double)height paper:(NCPPaperKind *)paper budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("createValidated(documentId:title:sessionNonce:width:height:paper:budget:ownerCheck:)")));

/** Validated adoption of one immutable root. No owner/IO/allocator failures are caught.
 * Validation creates no authority; only a successful result constructs the original notebook.
 */
- (NCPPreviewNotebookOpen *)openInitial:(NCPDocumentRoot *)initial sessionNonce:(NSString *)sessionNonce budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("open(initial:sessionNonce:budget:ownerCheck:)")));

/** Validate the entire immutable checkpoint before constructing the original authority. No auto-commit. */
- (NCPPreviewNotebookOpen *)openRecoverySnapshot:(NCPPagedPreviewSnapshot *)snapshot sessionNonce:(NSString *)sessionNonce budget:(NCPDocumentBudget *)budget ownerCheck:(void (^)(void))ownerCheck __attribute__((swift_name("openRecovery(snapshot:sessionNonce:budget:ownerCheck:)")));
@end


/** Closed immutable save inputs. The ledger never reads a mutable document or platform capability. */
__attribute__((swift_name("PreviewSaveSnapshot")))
@protocol NCPPreviewSaveSnapshot
@required

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NSString * _Nullable)encodeAndReturnError:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encode()")));
- (BOOL)sameContentOther:(id<NCPPreviewSaveSnapshot>)other __attribute__((swift_name("sameContent(other:)")));

/** Same-version replacement is restricted to a strict extension of the identical committed root. */
- (BOOL)strictlyExtendsOther:(id<NCPPreviewSaveSnapshot>)other __attribute__((swift_name("strictlyExtends(other:)")));
@property (readonly) NSString *documentId __attribute__((swift_name("documentId")));
@property (readonly) int64_t encodingWorkByteCharge __attribute__((swift_name("encodingWorkByteCharge")));
@property (readonly) int64_t retainedByteCharge __attribute__((swift_name("retainedByteCharge")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@property (readonly) int64_t version __attribute__((swift_name("version")));
@end


/** Immutable original save input. Recovery does not advance the committed document revision. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewSnapshot")))
@interface NCPPagedPreviewSnapshot : NCPBase <NCPPreviewSaveSnapshot>
@property (class, readonly, getter=companion) NCPPagedPreviewSnapshotCompanion *companion __attribute__((swift_name("companion")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NSString * _Nullable)encodeAndReturnError:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encode()")));
- (BOOL)sameContentOther:(id<NCPPreviewSaveSnapshot>)other __attribute__((swift_name("sameContent(other:)")));
- (BOOL)strictlyExtendsOther:(id<NCPPreviewSaveSnapshot>)other __attribute__((swift_name("strictlyExtends(other:)")));
@property (readonly) NSString *documentId __attribute__((swift_name("documentId")));
@property (readonly) int64_t encodingWorkByteCharge __attribute__((swift_name("encodingWorkByteCharge")));
@property (readonly) NCPDocumentCommandCanvas * _Nullable pending __attribute__((swift_name("pending")));
@property (readonly) int64_t retainedByteCharge __attribute__((swift_name("retainedByteCharge")));
@property (readonly) NCPDocumentRoot *root __attribute__((swift_name("root")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@property (readonly) int64_t version __attribute__((swift_name("version")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PagedPreviewSnapshot.Companion")))
@interface NCPPagedPreviewSnapshotCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPagedPreviewSnapshotCompanion *shared __attribute__((swift_name("shared")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPPagedPreviewSnapshot * _Nullable)fromRootRoot:(NCPDocumentRoot *)root error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("fromRoot(root:)")));
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
+ (NCPKotlinArray<NCPPaperKind *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPaperKind *> *entries __attribute__((swift_name("entries")));
@end


/** Unfrozen preview metadata sizing, not a heap measurement or production paged catalog.
 * Platform enumerators/URL internals remain outside this declared source-storage model.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewCatalogBudget")))
@interface NCPPreviewCatalogBudget : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/** Unfrozen preview metadata sizing, not a heap measurement or production paged catalog.
 * Platform enumerators/URL internals remain outside this declared source-storage model.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)previewCatalogBudget __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewCatalogBudget *shared __attribute__((swift_name("shared")));

/** 512 per row covers declared summary/container/sort-reference metadata; text is UTF-16.
 * Caller validates individual IDs <=128 and displayed titles <=125 before allocation/adoption.
 */
- (int64_t)resultByteChargeRows:(int32_t)rows textCodeUnits:(int64_t)textCodeUnits __attribute__((swift_name("resultByteCharge(rows:textCodeUnits:)")));

/** Reserve before enumeration: bounded path/URL/ID staging, one header workspace,
 * and old/new owner result overlap. Long arithmetic is safe for every nonnegative Int.
 * The original worker rejects work above its own maximum; no new queue is created.
 */
- (int64_t)workByteChargeDirectoryPathCodeUnits:(int32_t)directoryPathCodeUnits __attribute__((swift_name("workByteCharge(directoryPathCodeUnits:)")));
@property (readonly) int32_t maxDiskRows __attribute__((swift_name("maxDiskRows")));
@property (readonly) int32_t maxOwnerRows __attribute__((swift_name("maxOwnerRows")));
@property (readonly) int64_t resultByteLimit __attribute__((swift_name("resultByteLimit")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewDocumentRead")))
@interface NCPPreviewDocumentRead : NCPBase
@property (readonly) NCPPreviewReadFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPDocumentRoot * _Nullable root __attribute__((swift_name("root")));
@end


/** Pure synchronous validation over the original codec/root. No IO, mutable owner or callback.
 * Invalid interchange is a typed refusal; platform IO/owner/programmer failures are not caught here.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewDocumentReader")))
@interface NCPPreviewDocumentReader : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/** Pure synchronous validation over the original codec/root. No IO, mutable owner or callback.
 * Invalid interchange is a typed refusal; platform IO/owner/programmer failures are not caught here.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)previewDocumentReader __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewDocumentReader *shared __attribute__((swift_name("shared")));

/** Also gates already-retained immutable roots at the original host's adoption boundary. */
- (NCPPreviewDocumentRead *)acceptRoot:(NCPDocumentRoot *)root __attribute__((swift_name("accept(root:)")));
- (NCPPreviewDocumentRead *)readText:(NSString *)text __attribute__((swift_name("read(text:)")));
@end


/** Unfrozen raw-parameter facade over the existing notebook, never another content owner. */
__attribute__((swift_name("PreviewEdit")))
@protocol NCPPreviewEdit
@required
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditCopyPage")))
@interface NCPPreviewEditCopyPage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)getCopyPage __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditCopyPage *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditDeletePage")))
@interface NCPPreviewEditDeletePage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deletePage __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditDeletePage *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditInsertPage")))
@interface NCPPreviewEditInsertPage : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)insertPage __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditInsertPage *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditMovePage")))
@interface NCPPreviewEditMovePage : NCPBase <NCPPreviewEdit>
- (instancetype)initWithBeforePageId:(NSString * _Nullable)beforePageId __attribute__((swift_name("init(beforePageId:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditMovePage *)doCopyBeforePageId:(NSString * _Nullable)beforePageId __attribute__((swift_name("doCopy(beforePageId:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString * _Nullable beforePageId __attribute__((swift_name("beforePageId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditRedo")))
@interface NCPPreviewEditRedo : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)redo __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditRedo *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditRename")))
@interface NCPPreviewEditRename : NCPBase <NCPPreviewEdit>
- (instancetype)initWithTitle:(NSString *)title __attribute__((swift_name("init(title:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditRename *)doCopyTitle:(NSString *)title __attribute__((swift_name("doCopy(title:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *title __attribute__((swift_name("title")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSelectPage")))
@interface NCPPreviewEditSelectPage : NCPBase <NCPPreviewEdit>
- (instancetype)initWithPageId:(NSString *)pageId __attribute__((swift_name("init(pageId:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditSelectPage *)doCopyPageId:(NSString *)pageId __attribute__((swift_name("doCopy(pageId:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSString *pageId __attribute__((swift_name("pageId")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSelectPen")))
@interface NCPPreviewEditSelectPen : NCPBase <NCPPreviewEdit>
- (instancetype)initWithArgb:(int32_t)argb width:(double)width __attribute__((swift_name("init(argb:width:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditSelectPen *)doCopyArgb:(int32_t)argb width:(double)width __attribute__((swift_name("doCopy(argb:width:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) double width __attribute__((swift_name("width")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSetPaper")))
@interface NCPPreviewEditSetPaper : NCPBase <NCPPreviewEdit>
- (instancetype)initWithWidth:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("init(width:height:paper:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditSetPaper *)doCopyWidth:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("doCopy(width:height:paper:)")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) double height __attribute__((swift_name("height")));
@property (readonly) NCPPaperKind *paper __attribute__((swift_name("paper")));
@property (readonly) double width __attribute__((swift_name("width")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditUndo")))
@interface NCPPreviewEditUndo : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)undo __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditUndo *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end


/** Acceptance may be a presentation/tool/no-op action. The root revision is not a durable receipt. */
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
+ (NCPKotlinArray<NCPPreviewFailureKind *> *)values __attribute__((swift_name("values()")));
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
+ (NCPKotlinArray<NCPPreviewInputDisposition *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPreviewInputDisposition *> *entries __attribute__((swift_name("entries")));
@end


/** Synchronous admission on the original notebook owner, not a durable or display receipt.
 * REJECTED consumes zero new samples; caller owns the entire batch for retry or explicit cancellation.
 * PENDING_COMMIT owns all admitted points as the notebook's exact completed command: never resend the batch.
 */
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


/** Failure carries no mutable notebook; input root remains caller-owned and unchanged.
 * DocumentBudget itself is an already-validated value. Wrong owner and programmer errors propagate.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewNotebookOpen")))
@interface NCPPreviewNotebookOpen : NCPBase
@property (readonly) NCPDocumentRejection * _Nullable documentReason __attribute__((swift_name("documentReason")));
@property (readonly) NCPPreviewOpenFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPPagedPreviewNotebook * _Nullable notebook __attribute__((swift_name("notebook")));
@end


/** Unfrozen preview adoption boundary. Not a persistence, thread-transfer or stable SDK receipt. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewOpenFailure")))
@interface NCPPreviewOpenFailure : NCPKotlinEnum<NCPPreviewOpenFailure *>
+ (instancetype)alloc __attribute__((unavailable));

/** Unfrozen preview adoption boundary. Not a persistence, thread-transfer or stable SDK receipt. */
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
+ (NCPKotlinArray<NCPPreviewOpenFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPreviewOpenFailure *> *entries __attribute__((swift_name("entries")));
@end


/** Drawing data only. Checkpoint capture and retirement still use the original pending command. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewPendingInk")))
@interface NCPPreviewPendingInk : NCPBase
@property (readonly) NCPBrushSpec *brush __attribute__((swift_name("brush")));
@property (readonly) NCPStrokeSamples *samples __attribute__((swift_name("samples")));
@end


/** Unfrozen preview capability boundary, not the canonical format's complete support matrix. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewReadFailure")))
@interface NCPPreviewReadFailure : NCPKotlinEnum<NCPPreviewReadFailure *>
+ (instancetype)alloc __attribute__((unavailable));

/** Unfrozen preview capability boundary, not the canonical format's complete support matrix. */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPPreviewReadFailure *characterCapacity __attribute__((swift_name("characterCapacity")));
@property (class, readonly) NCPPreviewReadFailure *invalidOrUnsupportedFormat __attribute__((swift_name("invalidOrUnsupportedFormat")));
@property (class, readonly) NCPPreviewReadFailure *unsupportedPreviewContent __attribute__((swift_name("unsupportedPreviewContent")));
+ (NCPKotlinArray<NCPPreviewReadFailure *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPPreviewReadFailure *> *entries __attribute__((swift_name("entries")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewRecoveryCodec")))
@interface NCPPreviewRecoveryCodec : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)previewRecoveryCodec __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewRecoveryCodec *shared __attribute__((swift_name("shared")));

/** Strict framing bounds before allocating/decoding either canonical payload.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPPagedPreviewSnapshot * _Nullable)decodeText:(NSString *)text error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("decode(text:)")));

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NSString * _Nullable)encodeSnapshot:(NCPPagedPreviewSnapshot *)snapshot error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("encode(snapshot:)")));

/** Metadata is advisory only; full decoding is mandatory before opening.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPPagedPreviewHeader * _Nullable)headerText:(NSString *)text error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("header(text:)")));

/** Pure bounded content boundary. Decode/validation only; no IO, owner or callbacks to catch. */
- (NCPPreviewRecoveryRead *)readText:(NSString *)text __attribute__((swift_name("read(text:)")));
@end


/** Full immutable checkpoint or refusal; never a base-only fallback or mutable session. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewRecoveryRead")))
@interface NCPPreviewRecoveryRead : NCPBase
@property (readonly) NCPPreviewReadFailure * _Nullable failure __attribute__((swift_name("failure")));
@property (readonly) NCPPagedPreviewSnapshot * _Nullable snapshot __attribute__((swift_name("snapshot")));
@end


/** Caller remains on the original owner. Programmer/owner callback failures are not converted. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewSessionFailure")))
@interface NCPPreviewSessionFailure : NCPBase
@property (readonly) NCPDocumentRejection * _Nullable documentReason __attribute__((swift_name("documentReason")));
@property (readonly) NCPPreviewFailureKind *kind __attribute__((swift_name("kind")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinEnumCompanion")))
@interface NCPKotlinEnumCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPKotlinEnumCompanion *shared __attribute__((swift_name("shared")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinArray")))
@interface NCPKotlinArray<T> : NCPBase
+ (instancetype)arrayWithSize:(int32_t)size init:(T _Nullable (^)(NCPInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (T _Nullable)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (id<NCPKotlinIterator>)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(T _Nullable)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinDoubleArray")))
@interface NCPKotlinDoubleArray : NCPBase
+ (instancetype)arrayWithSize:(int32_t)size __attribute__((swift_name("init(size:)")));
+ (instancetype)arrayWithSize:(int32_t)size init:(NCPDouble *(^)(NCPInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (double)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (NCPKotlinDoubleIterator *)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(double)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinFloatArray")))
@interface NCPKotlinFloatArray : NCPBase
+ (instancetype)arrayWithSize:(int32_t)size __attribute__((swift_name("init(size:)")));
+ (instancetype)arrayWithSize:(int32_t)size init:(NCPFloat *(^)(NCPInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (float)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (NCPKotlinFloatIterator *)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(float)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinLongArray")))
@interface NCPKotlinLongArray : NCPBase
+ (instancetype)arrayWithSize:(int32_t)size __attribute__((swift_name("init(size:)")));
+ (instancetype)arrayWithSize:(int32_t)size init:(NCPLong *(^)(NCPInt *))init __attribute__((swift_name("init(size:init:)")));
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (int64_t)getIndex:(int32_t)index __attribute__((swift_name("get(index:)")));
- (NCPKotlinLongIterator *)iterator __attribute__((swift_name("iterator()")));
- (void)setIndex:(int32_t)index value:(int64_t)value __attribute__((swift_name("set(index:value:)")));
@property (readonly) int32_t size __attribute__((swift_name("size")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("KotlinPair")))
@interface NCPKotlinPair<__covariant A, __covariant B> : NCPBase
- (instancetype)initWithFirst:(A _Nullable)first second:(B _Nullable)second __attribute__((swift_name("init(first:second:)"))) __attribute__((objc_designated_initializer));
- (NCPKotlinPair<A, B> *)doCopyFirst:(A _Nullable)first second:(B _Nullable)second __attribute__((swift_name("doCopy(first:second:)")));
- (BOOL)equalsOther:(id _Nullable)other __attribute__((swift_name("equals(other:)")));
- (int32_t)hashCode __attribute__((swift_name("hashCode()")));
- (NSString *)toString __attribute__((swift_name("toString()")));
@property (readonly) A _Nullable first __attribute__((swift_name("first")));
@property (readonly) B _Nullable second __attribute__((swift_name("second")));
@end

__attribute__((swift_name("KotlinThrowable")))
@interface NCPKotlinThrowable : NCPBase
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));

/**
 * @note annotations
 *   kotlin.experimental.ExperimentalNativeApi
*/
- (NCPKotlinArray<NSString *> *)getStackTrace __attribute__((swift_name("getStackTrace()")));
- (void)printStackTrace __attribute__((swift_name("printStackTrace()")));
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPKotlinThrowable * _Nullable cause __attribute__((swift_name("cause")));
@property (readonly) NSString * _Nullable message __attribute__((swift_name("message")));
- (NSError *)asError __attribute__((swift_name("asError()")));
@end

__attribute__((swift_name("KotlinException")))
@interface NCPKotlinException : NCPKotlinThrowable
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinRuntimeException")))
@interface NCPKotlinRuntimeException : NCPKotlinException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinIllegalArgumentException")))
@interface NCPKotlinIllegalArgumentException : NCPKotlinRuntimeException
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (instancetype)initWithMessage:(NSString * _Nullable)message __attribute__((swift_name("init(message:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithCause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(cause:)"))) __attribute__((objc_designated_initializer));
- (instancetype)initWithMessage:(NSString * _Nullable)message cause:(NCPKotlinThrowable * _Nullable)cause __attribute__((swift_name("init(message:cause:)"))) __attribute__((objc_designated_initializer));
@end

__attribute__((swift_name("KotlinIterator")))
@protocol NCPKotlinIterator
@required
- (BOOL)hasNext __attribute__((swift_name("hasNext()")));
- (id _Nullable)next __attribute__((swift_name("next()")));
@end

__attribute__((swift_name("KotlinDoubleIterator")))
@interface NCPKotlinDoubleIterator : NCPBase <NCPKotlinIterator>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (NCPDouble *)next __attribute__((swift_name("next()")));
- (double)nextDouble __attribute__((swift_name("nextDouble()")));
@end

__attribute__((swift_name("KotlinFloatIterator")))
@interface NCPKotlinFloatIterator : NCPBase <NCPKotlinIterator>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (NCPFloat *)next __attribute__((swift_name("next()")));
- (float)nextFloat __attribute__((swift_name("nextFloat()")));
@end

__attribute__((swift_name("KotlinLongIterator")))
@interface NCPKotlinLongIterator : NCPBase <NCPKotlinIterator>
- (instancetype)init __attribute__((swift_name("init()"))) __attribute__((objc_designated_initializer));
+ (instancetype)new __attribute__((availability(swift, unavailable, message="use object initializers instead")));
- (NCPLong *)next __attribute__((swift_name("next()")));
- (int64_t)nextLong __attribute__((swift_name("nextLong()")));
@end

#pragma pop_macro("_Nullable_result")
#pragma clang diagnostic pop
NS_ASSUME_NONNULL_END
