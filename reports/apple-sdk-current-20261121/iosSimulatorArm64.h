#import <Foundation/NSArray.h>
#import <Foundation/NSDictionary.h>
#import <Foundation/NSError.h>
#import <Foundation/NSObject.h>
#import <Foundation/NSSet.h>
#import <Foundation/NSString.h>
#import <Foundation/NSValue.h>

@class NCPAffine2D, NCPApplePreviewInput, NCPBoundsD, NCPBrushBlendMode, NCPBrushDisplayRules, NCPBrushFillRule, NCPBrushGeometry, NCPBrushGeometryBudget, NCPBrushGeometryBuilder, NCPBrushGeometryRejection, NCPBrushGeometryResultReady, NCPBrushGeometryResultRejected, NCPBrushKind, NCPBrushPathPlan, NCPBrushPathPlanResultReady, NCPBrushPathPlanResultRejected, NCPBrushPathPlanner, NCPBrushPathPrecision, NCPBrushPathRejection, NCPBrushSpec, NCPCanvasCommandAddStroke, NCPCanvasCommandDeleteStroke, NCPCanvasCommandTransformStroke, NCPCommandId, NCPCommitReceipt, NCPCommitResultAccepted, NCPCommitResultRejected, NCPCompletedInput, NCPDocumentAuthorityStatus, NCPDocumentBudget, NCPDocumentBudgetCompanion, NCPDocumentBudgetCreation, NCPDocumentBudgetFailure, NCPDocumentCommandCanvas, NCPDocumentCommandDeletePage, NCPDocumentCommandInsertPage, NCPDocumentCommandMovePage, NCPDocumentCommandRedo, NCPDocumentCommandRename, NCPDocumentCommandSetPaper, NCPDocumentCommandUndo, NCPDocumentCommitAccepted, NCPDocumentCommitRejected, NCPDocumentReceipt, NCPDocumentRejection, NCPDocumentRoot, NCPDocumentRootCompanion, NCPDurability, NCPInputBatch, NCPInputRejection, NCPInputReplaySessionCompanion, NCPInputResultCancelled, NCPInputResultCompleted, NCPInputResultIgnoredNavigation, NCPInputResultPreview, NCPInputResultRejected, NCPInputSample, NCPKotlinArray<T>, NCPKotlinDoubleArray, NCPKotlinDoubleIterator, NCPKotlinEnum<E>, NCPKotlinEnumCompanion, NCPKotlinException, NCPKotlinFloatArray, NCPKotlinFloatIterator, NCPKotlinLongArray, NCPKotlinLongIterator, NCPKotlinRuntimeException, NCPKotlinThrowable, NCPLayerId, NCPObjectChange, NCPObjectId, NCPPageDescriptor, NCPPageId, NCPPagedPreviewCodec, NCPPagedPreviewHeader, NCPPagedPreviewNotebook, NCPPagedPreviewNotebookCompanion, NCPPagedPreviewSnapshot, NCPPagedPreviewSnapshotCompanion, NCPPaperKind, NCPPointD, NCPPointDCompanion, NCPPointerPhase, NCPPointerTool, NCPPolylineGeometry, NCPPrepareResultPrepared, NCPPrepareResultRejected, NCPPreviewCatalogBudget, NCPPreviewDocumentRead, NCPPreviewDocumentReader, NCPPreviewEditCopyPage, NCPPreviewEditDeletePage, NCPPreviewEditDeleteSelection, NCPPreviewEditEraseStrokes, NCPPreviewEditInsertPage, NCPPreviewEditLassoSelect, NCPPreviewEditMovePage, NCPPreviewEditRedo, NCPPreviewEditRename, NCPPreviewEditResult, NCPPreviewEditSelectPage, NCPPreviewEditSelectPen, NCPPreviewEditSetPaper, NCPPreviewEditTransformSelection, NCPPreviewEditUndo, NCPPreviewFailureKind, NCPPreviewInputDisposition, NCPPreviewInputResult, NCPPreviewNotebookOpen, NCPPreviewOpenFailure, NCPPreviewPendingInk, NCPPreviewReadFailure, NCPPreviewRecoveryCodec, NCPPreviewRecoveryRead, NCPPreviewSessionFailure, NCPRejection, NCPSceneApplicationApplied, NCPSceneApplicationRejected, NCPSceneId, NCPScenePatch, NCPSceneReducer, NCPSceneSnapshot, NCPSceneSnapshotCompanion, NCPStrokeObject, NCPStrokeSamples, NCPStrokeSamplesCompanion, NCPStrokeTarget, NSData;

@protocol NCPBrushGeometryResult, NCPBrushPathPlanResult, NCPCanvasCommand, NCPCommitResult, NCPDocumentCommand, NCPDocumentCommit, NCPInputResult, NCPKotlinComparable, NCPKotlinIterator, NCPPrepareResult, NCPPreviewEdit, NCPPreviewSaveSnapshot, NCPSceneApplication, NCPSceneObject;

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


/**
 * DEV-042 E3c display semantics at algorithm version 1. Canonical stroke content (samples, width,
 * argb) never changes; only the presented color differs by brush kind. HIGHLIGHTER renders the
 * same color at half alpha on both platforms so saved documents stay visually consistent.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("BrushDisplayRules")))
@interface NCPBrushDisplayRules : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/**
 * DEV-042 E3c display semantics at algorithm version 1. Canonical stroke content (samples, width,
 * argb) never changes; only the presented color differs by brush kind. HIGHLIGHTER renders the
 * same color at half alpha on both platforms so saved documents stay visually consistent.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)brushDisplayRules __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPBrushDisplayRules *shared __attribute__((swift_name("shared")));
- (int32_t)displayArgbBrush:(NCPBrushSpec *)brush __attribute__((swift_name("displayArgb(brush:)")));
@property (readonly) float HIGHLIGHTER_ALPHA __attribute__((swift_name("HIGHLIGHTER_ALPHA")));
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
__attribute__((swift_name("Durability")))
@interface NCPDurability : NCPKotlinEnum<NCPDurability *>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
- (instancetype)initWithName:(NSString *)name ordinal:(int32_t)ordinal __attribute__((swift_name("init(name:ordinal:)"))) __attribute__((objc_designated_initializer)) __attribute__((unavailable));
@property (class, readonly) NCPDurability *memoryOnly __attribute__((swift_name("memoryOnly")));
+ (NCPKotlinArray<NCPDurability *> *)values __attribute__((swift_name("values()")));
@property (class, readonly) NSArray<NCPDurability *> *entries __attribute__((swift_name("entries")));
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


/** C1 reference geometry, not a frozen public API or a rendering implementation. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PointD")))
@interface NCPPointD : NCPBase
- (instancetype)initWithX:(double)x y:(double)y __attribute__((swift_name("init(x:y:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPPointDCompanion *companion __attribute__((swift_name("companion")));
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
__attribute__((swift_name("PointD.Companion")))
@interface NCPPointDCompanion : NCPBase
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)companion __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPointDCompanion *shared __attribute__((swift_name("shared")));

/** Recoverable raw-input boundary; does not clamp or reinterpret finite coordinates. */
- (NCPPointD * _Nullable)validatedX:(double)x y:(double)y __attribute__((swift_name("validated(x:y:)")));
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


/**
 * Shared deterministic polyline/polygon predicates for DEV-042 editing rule candidates.
 * Pure functions on immutable inputs; no scene state, budgets or platform geometry version.
 * Public within the unfrozen prototype package, same as PointD/Affine2D; not a stable SDK surface.
 */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PolylineGeometry")))
@interface NCPPolylineGeometry : NCPBase
+ (instancetype)alloc __attribute__((unavailable));

/**
 * Shared deterministic polyline/polygon predicates for DEV-042 editing rule candidates.
 * Pure functions on immutable inputs; no scene state, budgets or platform geometry version.
 * Public within the unfrozen prototype package, same as PointD/Affine2D; not a stable SDK surface.
 */
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)polylineGeometry __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPolylineGeometry *shared __attribute__((swift_name("shared")));
- (double)distanceSquaredA:(NCPPointD *)a b:(NCPPointD *)b __attribute__((swift_name("distanceSquared(a:b:)")));

/** Largest singular value of the linear part: exact for rotation/uniform scale, conservative otherwise. */
- (double)maxScaleT:(NCPAffine2D *)t __attribute__((swift_name("maxScale(t:)")));
- (BOOL)overlapsA:(NCPBoundsD *)a b:(NCPBoundsD *)b __attribute__((swift_name("overlaps(a:b:)")));

/** Even-odd ray cast. Points exactly on an edge are covered by segment distance checks instead. */
- (BOOL)pointInPolygonP:(NCPPointD *)p polygon:(NSArray<NCPPointD *> *)polygon __attribute__((swift_name("pointInPolygon(p:polygon:)")));
- (double)pointSegmentDistanceSquaredP:(NCPPointD *)p a:(NCPPointD *)a b:(NCPPointD *)b __attribute__((swift_name("pointSegmentDistanceSquared(p:a:b:)")));
- (BOOL)polylinesWithinA:(NSArray<NCPPointD *> *)a b:(NSArray<NCPPointD *> *)b threshold:(double)threshold __attribute__((swift_name("polylinesWithin(a:b:threshold:)")));
- (int32_t)segmentCountPoints:(int32_t)points __attribute__((swift_name("segmentCount(points:)")));
- (double)segmentDistanceSquaredP1:(NCPPointD *)p1 p2:(NCPPointD *)p2 q1:(NCPPointD *)q1 q2:(NCPPointD *)q2 __attribute__((swift_name("segmentDistanceSquared(p1:p2:q1:q2:)")));
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

__attribute__((swift_name("SceneObject")))
@protocol NCPSceneObject
@required
- (NCPBoundsD *)bounds __attribute__((swift_name("bounds()")));
@property (readonly) NCPObjectId *id __attribute__((swift_name("id")));
@property (readonly) NCPLayerId *layerId __attribute__((swift_name("layerId")));
@property (readonly) int64_t objectRevision __attribute__((swift_name("objectRevision")));
@property (readonly) NCPAffine2D *transform __attribute__((swift_name("transform")));
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
- (id<NCPSceneObject> _Nullable)findObjectId:(NCPObjectId *)id __attribute__((swift_name("findObject(id:)")));
- (void)forEachObjectVisit:(void (^)(NCPStrokeObject *))visit __attribute__((swift_name("forEachObject(visit:)")));

/** Synchronous immutable inspection without a full-scene list copy; never exposes the map.
 * Unfrozen snapshot read surface. Callers own any references retained by their visitor.
 */
- (void)forEachSceneObjectVisit:(void (^)(id<NCPSceneObject>))visit __attribute__((swift_name("forEachSceneObject(visit:)")));
- (NSArray<NCPStrokeObject *> *)objects __attribute__((swift_name("objects()")));

/** Immutable content comparison preserves draw order without two full-scene reference lists.
 * This is a read operation, not an authority or an unversioned cache identity.
 */
- (BOOL)sameContentOther:(NCPSceneSnapshot *)other __attribute__((swift_name("sameContent(other:)")));
- (NSArray<id<NCPSceneObject>> *)sceneObjects __attribute__((swift_name("sceneObjects()")));
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
- (NCPSceneSnapshot *)restoredObjectsId:(NCPSceneId *)id revision:(int64_t)revision objects:(NSArray<id<NCPSceneObject>> *)objects __attribute__((swift_name("restoredObjects(id:revision:objects:)")));
@end

__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("StrokeObject")))
@interface NCPStrokeObject : NCPBase <NCPSceneObject>
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

/** A single local sample value, or null for an index outside this immutable stroke. */
- (NCPInputSample * _Nullable)sampleAtIndex:(int32_t)index __attribute__((swift_name("sampleAt(index:)")));
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

/**
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (instancetype _Nullable)initWithMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("init(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)"))) __attribute__((objc_designated_initializer));
@property (class, readonly, getter=companion) NCPDocumentBudgetCompanion *companion __attribute__((swift_name("companion")));

/** Explicit so invalid copies use the same native error channel as construction.
 *
 * @note This method converts instances of IllegalArgumentException to errors.
 * Other uncaught Kotlin exceptions are fatal.
*/
- (NCPDocumentBudget * _Nullable)doCopyMaxPages:(int32_t)maxPages maxObjects:(int32_t)maxObjects liveBytes:(int64_t)liveBytes journalEntries:(int32_t)journalEntries journalBytes:(int64_t)journalBytes identityBytes:(int64_t)identityBytes historyEntries:(int32_t)historyEntries historyBytes:(int64_t)historyBytes inputBytes:(int64_t)inputBytes error:(NSError * _Nullable * _Nullable)error __attribute__((swift_name("doCopy(maxPages:maxObjects:liveBytes:journalEntries:journalBytes:identityBytes:historyEntries:historyBytes:inputBytes:)")));
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
- (NCPPageDescriptor * _Nullable)pageAtIndex:(int32_t)index __attribute__((swift_name("pageAt(index:)")));
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
- (BOOL)deleteSelection __attribute__((swift_name("deleteSelection()")));

/** All request payloads are raw values. Validation precedes cancellation or identity allocation. */
- (NCPPreviewEditResult *)editRequest:(id<NCPPreviewEdit>)request __attribute__((swift_name("edit(request:)")));
- (BOOL)endStrokeX:(double)x y:(double)y pressure:(float)pressure timeMicros:(int64_t)timeMicros __attribute__((swift_name("endStroke(x:y:pressure:timeMicros:)")));

/** One eraser gesture deletes every hit as one PreparedCanvas command, hence one undo entry. */
- (BOOL)eraseStrokesPath:(NSArray<NCPPointD *> *)path radius:(double)radius __attribute__((swift_name("eraseStrokes(path:radius:)")));
- (BOOL)insertPage __attribute__((swift_name("insertPage()")));

/** Lasso selection is presentation-only: no command, no revision change, no history entry. */
- (BOOL)lassoSelectPolygon:(NSArray<NCPPointD *> *)polygon __attribute__((swift_name("lassoSelect(polygon:)")));
- (BOOL)moveCurrentPageBefore:(NCPPageId * _Nullable)before __attribute__((swift_name("moveCurrentPage(before:)")));
- (NCPPageDescriptor *)pageIndex:(int32_t)index __attribute__((swift_name("page(index:)")));

/** Owner-only lookup. An out-of-range index is absence and does not change the session. */
- (NCPPageDescriptor * _Nullable)pageAtIndex:(int32_t)index __attribute__((swift_name("pageAt(index:)")));

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
- (BOOL)selectPenArgb:(int32_t)argb width:(double)width kind:(NCPBrushKind *)kind __attribute__((swift_name("selectPen(argb:width:kind:)")));
- (BOOL)setPaperWidth:(double)width height:(double)height paper:(NCPPaperKind *)paper __attribute__((swift_name("setPaper(width:height:paper:)")));

/** Mode changes never cancel accepted input or resolve a retained completion implicitly. */
- (BOOL)setReadOnlyValue:(BOOL)value __attribute__((swift_name("setReadOnly(value:)")));
- (NCPDocumentRoot *)snapshot __attribute__((swift_name("snapshot()")));
- (NCPStrokeObject *)strokeIndex:(int32_t)index __attribute__((swift_name("stroke(index:)")));

/** Owner-only lookup in the selected page; the returned stroke is immutable. */
- (NCPStrokeObject * _Nullable)strokeAtIndex:(int32_t)index __attribute__((swift_name("strokeAt(index:)")));
- (int32_t)strokeColorIndex:(int32_t)index __attribute__((swift_name("strokeColor(index:)")));
- (NSString *)strokeIdIndex:(int32_t)index __attribute__((swift_name("strokeId(index:)")));
- (double)strokeWidthIndex:(int32_t)index __attribute__((swift_name("strokeWidth(index:)")));

/** One transform command for the whole selection, hence one undo entry; selection stays for further moves. */
- (BOOL)transformSelectionDx:(double)dx dy:(double)dy scaleFactor:(double)scaleFactor anchor:(NCPPointD *)anchor __attribute__((swift_name("transformSelection(dx:dy:scaleFactor:anchor:)")));
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
@property (readonly) NCPPreviewSessionFailure * _Nullable lastFailure __attribute__((swift_name("lastFailure")));
@property (readonly) int32_t pageCount __attribute__((swift_name("pageCount")));

/** Presentation-only lasso selection; invalidated by page switches and history traversal. */
@property (readonly) NSArray<NCPObjectId *> *selectedStrokeIds __attribute__((swift_name("selectedStrokeIds")));
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
__attribute__((swift_name("PreviewEditDeleteSelection")))
@interface NCPPreviewEditDeleteSelection : NCPBase <NCPPreviewEdit>
+ (instancetype)alloc __attribute__((unavailable));
+ (instancetype)allocWithZone:(struct _NSZone *)zone __attribute__((unavailable));
+ (instancetype)deleteSelection __attribute__((swift_name("init()")));
@property (class, readonly, getter=shared) NCPPreviewEditDeleteSelection *shared __attribute__((swift_name("shared")));
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));
- (NSUInteger)hash __attribute__((swift_name("hash()")));
- (NSString *)description __attribute__((swift_name("description()")));
@end


/** Whole-stroke eraser gesture on the current page; a miss is an accepted no-op without history. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditEraseStrokes")))
@interface NCPPreviewEditEraseStrokes : NCPBase <NCPPreviewEdit>
- (instancetype)initWithPath:(NSArray<NCPPointD *> *)path radius:(double)radius __attribute__((swift_name("init(path:radius:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditEraseStrokes *)doCopyPath:(NSArray<NCPPointD *> *)path radius:(double)radius __attribute__((swift_name("doCopy(path:radius:)")));

/** Whole-stroke eraser gesture on the current page; a miss is an accepted no-op without history. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Whole-stroke eraser gesture on the current page; a miss is an accepted no-op without history. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Whole-stroke eraser gesture on the current page; a miss is an accepted no-op without history. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSArray<NCPPointD *> *path __attribute__((swift_name("path")));
@property (readonly) double radius __attribute__((swift_name("radius")));
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


/** Presentation-only lasso on the current page; an empty hit clears the selection without history. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditLassoSelect")))
@interface NCPPreviewEditLassoSelect : NCPBase <NCPPreviewEdit>
- (instancetype)initWithPolygon:(NSArray<NCPPointD *> *)polygon __attribute__((swift_name("init(polygon:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditLassoSelect *)doCopyPolygon:(NSArray<NCPPointD *> *)polygon __attribute__((swift_name("doCopy(polygon:)")));

/** Presentation-only lasso on the current page; an empty hit clears the selection without history. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Presentation-only lasso on the current page; an empty hit clears the selection without history. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Presentation-only lasso on the current page; an empty hit clears the selection without history. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NSArray<NCPPointD *> *polygon __attribute__((swift_name("polygon")));
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


/** Kind defaults to MONOLINE for legacy callers; PRESSURE stays closed at algorithm version 1. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditSelectPen")))
@interface NCPPreviewEditSelectPen : NCPBase <NCPPreviewEdit>
- (instancetype)initWithArgb:(int32_t)argb width:(double)width kind:(NCPBrushKind *)kind __attribute__((swift_name("init(argb:width:kind:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditSelectPen *)doCopyArgb:(int32_t)argb width:(double)width kind:(NCPBrushKind *)kind __attribute__((swift_name("doCopy(argb:width:kind:)")));

/** Kind defaults to MONOLINE for legacy callers; PRESSURE stays closed at algorithm version 1. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Kind defaults to MONOLINE for legacy callers; PRESSURE stays closed at algorithm version 1. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Kind defaults to MONOLINE for legacy callers; PRESSURE stays closed at algorithm version 1. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) int32_t argb __attribute__((swift_name("argb")));
@property (readonly) NCPBrushKind *kind __attribute__((swift_name("kind")));
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


/** Uniform scale around the anchor, then translation, composed once for the whole selection. */
__attribute__((objc_subclassing_restricted))
__attribute__((swift_name("PreviewEditTransformSelection")))
@interface NCPPreviewEditTransformSelection : NCPBase <NCPPreviewEdit>
- (instancetype)initWithDx:(double)dx dy:(double)dy scaleFactor:(double)scaleFactor anchor:(NCPPointD *)anchor __attribute__((swift_name("init(dx:dy:scaleFactor:anchor:)"))) __attribute__((objc_designated_initializer));
- (NCPPreviewEditTransformSelection *)doCopyDx:(double)dx dy:(double)dy scaleFactor:(double)scaleFactor anchor:(NCPPointD *)anchor __attribute__((swift_name("doCopy(dx:dy:scaleFactor:anchor:)")));

/** Uniform scale around the anchor, then translation, composed once for the whole selection. */
- (BOOL)isEqual:(id _Nullable)other __attribute__((swift_name("isEqual(_:)")));

/** Uniform scale around the anchor, then translation, composed once for the whole selection. */
- (NSUInteger)hash __attribute__((swift_name("hash()")));

/** Uniform scale around the anchor, then translation, composed once for the whole selection. */
- (NSString *)description __attribute__((swift_name("description()")));
@property (readonly) NCPPointD *anchor __attribute__((swift_name("anchor")));
@property (readonly) double dx __attribute__((swift_name("dx")));
@property (readonly) double dy __attribute__((swift_name("dy")));
@property (readonly) double scaleFactor __attribute__((swift_name("scaleFactor")));
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
@property (class, readonly) NCPPreviewFailureKind *unsupportedBrush __attribute__((swift_name("unsupportedBrush")));
@property (class, readonly) NCPPreviewFailureKind *emptySelection __attribute__((swift_name("emptySelection")));
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
