package com.github.curiousoddman.curious_images.util.async.jobs;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.domain.ai.AiPipelineJob;
import com.github.curiousoddman.curious_images.domain.ai.AlbumGenerationJob;
import com.github.curiousoddman.curious_images.domain.ai.ArcFaceEncoder;
import com.github.curiousoddman.curious_images.domain.ai.ClipImageEncoder;
import com.github.curiousoddman.curious_images.domain.ai.ClipTextEncoder;
import com.github.curiousoddman.curious_images.domain.ai.FaceAligner;
import com.github.curiousoddman.curious_images.domain.ai.ModelDownloadJob;
import com.github.curiousoddman.curious_images.domain.ai.ModelPaths;
import com.github.curiousoddman.curious_images.domain.ai.PersonClusteringService;
import com.github.curiousoddman.curious_images.domain.ai.RetinaFaceDetector;
import com.github.curiousoddman.curious_images.domain.ai.VideoFrameSampler;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.PersonService;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerationJob;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.VideoThumbnailGenerator;
import com.github.curiousoddman.curious_images.domain.dedupe.DuplicateDetectionJob;
import com.github.curiousoddman.curious_images.domain.dedupe.hasher.FileHasher;
import com.github.curiousoddman.curious_images.domain.dedupe.hasher.PixelHasher;
import com.github.curiousoddman.curious_images.domain.imports.AddFilesJob;
import com.github.curiousoddman.curious_images.domain.imports.ImportJob;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.imports.metadata.StatsSessionFactory;
import com.github.curiousoddman.curious_images.domain.imports.metadata.VideoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.domain.scenegroup.SceneGroupingJob;
import com.github.curiousoddman.curious_images.domain.scenegroup.SceneGroupingService;
import com.github.curiousoddman.curious_images.model.AddFilesRequest;
import com.github.curiousoddman.curious_images.persistence.AlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateJobRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FaceThumbnailsRepository;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.MediaHashRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class JobFactory {
    private final DSLContext                  dsl;
    private final ImportRootRepository        importRootRepository;
    private final FolderRepository            folderRepository;
    private final MediaRepository             mediaRepository;
    private final ThumbnailRepository         thumbnailRepository;
    private final PhotoPreviewRepository      photoPreviewRepository;
    private final PhotoMetadataExtractor      photoMetadataExtractor;
    private final VideoMetadataExtractor      videoMetadataExtractor;
    private final ThumbnailGenerator          thumbnailGenerator;
    private final VideoThumbnailGenerator     videoThumbnailGenerator;
    private final ImageUtils                  imageUtils;
    private final VideoFrameSampler           videoFrameSampler;
    private final TimeProvider                timeProvider;
    private final MediaHashRepository         photoHashRepository;
    private final DuplicateJobRepository      duplicateJobRepository;
    private final DuplicateGroupRepository    duplicateGroupRepository;
    private final PixelHasher                 pixelHasher;
    private final FileHasher                  fileHasher;
    private final FaceRepository              faceRepository;
    private final FaceEmbeddingRepository     faceEmbeddingRepository;
    private final ClipEmbeddingRepository     clipEmbeddingRepository;
    private final RetinaFaceDetector          retinaFaceDetector;
    private final ArcFaceEncoder              arcFaceEncoder;
    private final FaceAligner                 faceAligner;
    private final ClipImageEncoder            clipImageEncoder;
    private final ClipVectorIndex             clipVectorIndex;
    private final FaceVectorIndex             faceVectorIndex;
    private final PersonClusteringService     personClusteringService;
    private final FaceThumbnailsRepository    faceThumbnailsRepository;
    private final AlbumRepository             albumRepository;
    private final AlbumPhotoRepository        albumPhotoRepository;
    private final AiConfig                    aiConfig;
    private final ClusterRepository           clusterRepository;
    private final PersonService               personService;
    private final ModelPaths                  modelPaths;
    private final ClipTextEncoder             clipTextEncoder;
    private final PhotoTagRepository          photoTagRepository;
    private final StatsSessionFactory         statsSessionFactory;
    private final MediaMetadataEditRepository mediaMetadataEditRepository;
    private final CustomAlbumPhotoRepository  customAlbumPhotoRepository;
    private final SceneGroupingService        sceneGroupingService;

    public ImportJob createImportJob(List<String> paths) {
        return new ImportJob(
                dsl,
                importRootRepository,
                folderRepository,
                mediaRepository,
                mediaMetadataEditRepository,
                photoPreviewRepository,
                photoMetadataExtractor,
                videoMetadataExtractor,
                timeProvider,
                paths,
                statsSessionFactory
        );
    }

    /**
     * Supersedable, on-demand real-thumbnail generation for a page/selection of media IDs — see
     * implementation plan §5/§6. Submitted by {@code LibraryController} whenever the grid is
     * about to render a set of media IDs, never as a bulk sweep.
     */
    public ThumbnailGenerationJob createThumbnailGenerationJob(List<Long> photoIds) {
        return new ThumbnailGenerationJob(
                mediaRepository,
                thumbnailRepository,
                imageUtils,
                videoThumbnailGenerator,
                thumbnailGenerator,
                timeProvider,
                photoIds
        );
    }

    public DuplicateDetectionJob createDuplicateDetectionJob() {
        return new DuplicateDetectionJob(
                dsl,
                mediaRepository,
                photoHashRepository,
                duplicateJobRepository,
                duplicateGroupRepository,
                pixelHasher,
                fileHasher,
                timeProvider,
                aiConfig.getDuplicateDetectionThreadCount()
        );
    }

    public AiPipelineJob createAiPipelineJob(JobManager jobManager) {
        return new AiPipelineJob(
                dsl,
                mediaRepository,
                faceRepository,
                clusterRepository,
                faceEmbeddingRepository,
                clipEmbeddingRepository,
                retinaFaceDetector,
                arcFaceEncoder,
                faceAligner,
                clipImageEncoder,
                clipVectorIndex,
                faceVectorIndex,
                personClusteringService,
                timeProvider,
                faceThumbnailsRepository,
                jobManager,
                imageUtils,
                videoFrameSampler,
                aiConfig.isFaceOnly(),
                aiConfig.getVideoFrameSampleCount(),
                aiConfig.getVideoFrameSampleIntervalSeconds(),
                clipTextEncoder,
                photoTagRepository
        );
    }

    public AddFilesJob createAddFilesJob(AddFilesRequest request, JobManager jobManager) {
        return new AddFilesJob(
                createImportJob(List.of()),
                jobManager,
                request,
                statsSessionFactory
        );
    }

    public AlbumGenerationJob createAlbumGenerationJob() {
        return new AlbumGenerationJob(
                dsl,
                albumRepository,
                albumPhotoRepository,
                clipEmbeddingRepository,
                aiConfig,
                timeProvider,
                personService,
                mediaRepository
        );
    }

    /**
     * Global sweep — see {@link SceneGroupingJob} class javadoc for why this isn't parameterized
     * to a single album.
     */
    public SceneGroupingJob createSceneGroupingJob() {
        return new SceneGroupingJob(
                customAlbumPhotoRepository,
                sceneGroupingService
        );
    }

    /**
     * @param onSuccess invoked once all missing models finish downloading. Pass
     *                  {@code () -> {}} for a plain background download (e.g. the startup
     *                  prompt) or {@code jobManager::submitAiPipelineJob} to auto-chain into the
     *                  AI pipeline once models are ready.
     */
    public ModelDownloadJob createModelDownloadJob(Runnable onSuccess) {
        return new ModelDownloadJob(
                modelPaths,
                aiConfig,
                onSuccess
        );
    }
}
