package de.tstieh.stoneintelligence.platform.comment;

import de.tstieh.stoneintelligence.domain.id.NoteId;
import de.tstieh.stoneintelligence.domain.id.VaultId;

class FakeCommentRepositoryTest extends CommentRepositoryContractTest {

    @Override
    protected CommentRepository repository() {
        return new FakeCommentRepository();
    }

    @Override
    protected Target newNote() {
        return new Target(VaultId.newId(), NoteId.newId());
    }
}
