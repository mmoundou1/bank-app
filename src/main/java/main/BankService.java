package main;

public class BankService {

    private BankAccountsDb bankAccountsDb;

    public BankService() {}

    public BankService(BankAccountsDb bankAccountsDb) {
        this.bankAccountsDb = bankAccountsDb;

    }

    public Account authenticate(String username, String password) {

        return bankAccountsDb.findAcc(username, password);

    }

    public double getBalance(Account account) {

        return 0.0;

    }

    public void deposit(Account account, double amount) {

    }

    public boolean withdraw(Account account, double amount, BankAccountsDb bankAccountsDb) {
        return false;
    }

}
