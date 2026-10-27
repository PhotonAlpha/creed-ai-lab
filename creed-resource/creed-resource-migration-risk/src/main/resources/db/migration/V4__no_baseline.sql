-- repo1 HEAD is compared with repo2 directly: new runs have no baseline. The column stays for runs saved before.
alter table mr_analysis alter column baseline_ref drop not null;
