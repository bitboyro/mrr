package ro.bitboy.mrr.repo;

import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A repository that counts how many blobs are read through it.
 *
 * <p>Exists for one assertion: the co-change indexer never opens a blob. That is a claim about
 * what mrr does with a customer's source, made in the README, and a claim like that should be
 * enforced by the build rather than by whoever edits the indexer next.
 */
final class CountingRepository extends FileRepository {

    private final AtomicInteger blobReads = new AtomicInteger();

    CountingRepository(Repository from) throws IOException {
        super(from.getDirectory());
    }

    int blobReads() {
        return blobReads.get();
    }

    @Override
    public ObjectReader newObjectReader() {
        ObjectReader delegate = super.newObjectReader();
        return new ObjectReader.Filter() {
            @Override
            protected ObjectReader delegate() {
                return delegate;
            }

            @Override
            public ObjectLoader open(AnyObjectId objectId, int typeHint) throws IOException {
                ObjectLoader loader = delegate.open(objectId, typeHint);
                if (loader.getType() == Constants.OBJ_BLOB) {
                    blobReads.incrementAndGet();
                }
                return loader;
            }
        };
    }
}
