package de.tstieh.stoneintelligence.platform.identity;

class FakePeopleDirectoryTest extends PeopleDirectoryContractTest {

    @Override
    protected PeopleDirectory people() {
        return new FakePeopleDirectory();
    }
}
