package tv.cleanyour.app

import tv.cleanyour.core.backup.RestoreBundleWriter
import tv.cleanyour.core.backup.RestoreNote
import tv.cleanyour.core.backup.TvRestoreFiles
import tv.cleanyour.core.engine.Engine
import tv.cleanyour.core.engine.Journal
import tv.cleanyour.core.flows.KeyInfo
import tv.cleanyour.core.planner.Planner
import tv.cleanyour.core.probe.Prober
import tv.cleanyour.core.registry.ArtifactFetcher
import tv.cleanyour.core.registry.HttpFetcher
import tv.cleanyour.core.registry.Registry
import tv.cleanyour.core.session.SessionManager
import tv.cleanyour.core.spec.FactsTable
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.spec.ThemeSource
import tv.cleanyour.core.store.BlobStore
import tv.cleanyour.core.store.SnapshotStore
import tv.cleanyour.core.trust.ApkInspector

// The implementations of section 5 that other streams own, as AppGraph wires them (T7, all real):
//   sessions      W2 AdbSessionManager(PlainSocketFactory, DeferredAdbSigner over KeystoreAdbSigner, tls = null)
//   keyInfo       W2 KeystoreAdbSigner through SignerKeyInfo (fingerprint, backing, reset)
//   prober, profiles, facts, themes  W4 AdbProber, BundledProfileSource, BundledFactsTable,
//                 BundledThemeSource behind MaterializedThemeSource (wallpapers as plain files)
//   planner, engine, journal  W3 DefaultPlanner(..., profiles = profiles),
//                 DefaultEngine behind LiveSessionEngine, FileJournal
//   snapshots, restoreFiles, restoreNote, bundleWriter  W6 DocumentSnapshotStore, DefaultTvRestoreFiles,
//                 PlainRestoreNote, ZipRestoreBundleWriter with the real ShellRenderer
//   blobs, registry, fetcher, inspector, http  W5 FileBlobStore,
//                 IndexRegistry(sources = null), ResumableArtifactFetcher behind InstallableOnlyFetcher,
//                 PackageManagerApkInspector, UrlConnectionFetcher
data class Seams(
    val sessions: SessionManager,
    val keyInfo: KeyInfo,
    val prober: Prober,
    val profiles: ProfileSource,
    val facts: FactsTable,
    val themes: ThemeSource,
    val planner: Planner,
    val engine: Engine,
    val journal: Journal,
    val snapshots: SnapshotStore,
    val restoreFiles: TvRestoreFiles,
    val restoreNote: RestoreNote,
    val bundleWriter: RestoreBundleWriter,
    val blobs: BlobStore,
    val registry: Registry,
    val fetcher: ArtifactFetcher,
    val inspector: ApkInspector,
    val http: HttpFetcher,
)
