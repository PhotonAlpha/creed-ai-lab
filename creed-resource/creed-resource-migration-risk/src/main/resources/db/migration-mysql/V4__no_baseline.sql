-- repo1 HEAD is compared with repo2 directly: new runs have no baseline. The column stays for runs saved before.
alter table mr_analysis modify baseline_ref varchar(1000) null;
