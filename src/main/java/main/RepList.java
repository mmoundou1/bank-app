package main;

import java.util.LinkedList;
import java.util.Random;

public class RepList {

    private LinkedList<Rep> repList;

    public LinkedList<Rep> getRepList() {
        return repList;
    }

    public void setRepList(LinkedList<Rep> repList) {
        this.repList = repList;
    }

    public Rep getAvailableRep() {

        Random rand = new Random();
        int randomNum = rand.nextInt(3);

        return repList.get(randomNum);

    }

}
