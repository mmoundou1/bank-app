package main;

import java.util.LinkedList;

public class RepDb {

    private RepList repList;
    private LinkedList<Rep> innerRepList;

    public RepDb() {
        innerRepList = new LinkedList<>();
        repList = new RepList();

        innerRepList.add(new Rep("Matt", 111));
        innerRepList.add(new Rep("Stephanie", 222));
        innerRepList.add(new Rep("Jeremy", 333));

        repList.setRepList(innerRepList);
    }

    public Rep findRep() {

        return repList.getAvailableRep();

    }

}
