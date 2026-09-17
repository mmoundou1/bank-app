package main;

public class RepService {

    private RepDb repDb;

    public RepService() {}

    public RepService(RepDb repDb) {
        this.repDb = repDb;
    }

    public Rep getAvailableRep() {

        return repDb.findRep();

    }

}
