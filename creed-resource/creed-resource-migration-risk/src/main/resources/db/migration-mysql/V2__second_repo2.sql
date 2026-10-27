-- repo2 can be two repositories (repo2A + repo2B, compared as one tree). repo2_path keeps repo2A.
alter table mr_analysis add column repo2b_path varchar(1000);
