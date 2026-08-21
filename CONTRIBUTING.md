# Contributing to IntelliStream DataHub

Thanks for your interest in contributing. This document covers the legal basics for getting your changes accepted.

## License

IntelliStream DataHub is licensed under the **GNU Affero General Public License v3.0** (AGPL-3.0). See [LICENSE](LICENSE) for the full text.

By contributing, you agree that your contributions will be licensed under the same terms.

## Developer Certificate of Origin (DCO)

We use the [Developer Certificate of Origin](https://developercertificate.org/) (DCO) to confirm that contributors have the right to submit the code they're contributing. There is no separate contributor license agreement (CLA) to sign.

The DCO is a short statement that you assert by signing off on every commit. The full text is:

> Developer Certificate of Origin
> Version 1.1
>
> By making a contribution to this project, I certify that:
>
> (a) The contribution was created in whole or in part by me and I
>     have the right to submit it under the open source license
>     indicated in the file; or
>
> (b) The contribution is based upon previous work that, to the best
>     of my knowledge, is covered under an appropriate open source
>     license and I have the right under that license to submit that
>     work with modifications, whether created in whole or in part
>     by me, under the same open source license (unless I am
>     permitted to submit under a different license), as indicated
>     in the file; or
>
> (c) The contribution was provided directly to me by some other
>     person who certified (a), (b) or (c) and I have not modified
>     it.
>
> (d) I understand and agree that this project and the contribution
>     are public and that a record of the contribution (including all
>     personal information I submit with it, including my sign-off) is
>     maintained indefinitely and may be redistributed consistent with
>     this project or the open source license(s) involved.

### How to sign off

Add a `Signed-off-by:` trailer to every commit message, using your real name and an email address you can be reached at. Git does this for you:

```
git commit -s -m "Fix concurrent update race in ResourceService"
```

The `-s` (or `--signoff`) flag appends a line like:

```
Signed-off-by: Jane Contributor <jane@example.com>
```

You can set `git config --global format.signOff true` to make this the default for every commit you author.

### If you forgot to sign off

For the most recent commit:

```
git commit --amend --signoff
```

For a range of commits on a branch:

```
git rebase --signoff master
```

Then force-push the branch.

### Enforcement

Every commit must carry a valid `Signed-off-by:` trailer whose name and email match the commit author. Maintainers check this during review, and a pull request won't be merged until every commit is signed off. If you forgot, fix it with the steps above and push again.

## Submitting changes

1. Fork the repository and create a feature branch.
2. Make your changes. Keep commits focused and readable.
3. Sign off on every commit (`git commit -s`).
4. Open a pull request against `master` with a clear description of what the change does and why.

## Questions

If anything about the DCO or the license is unclear, open an issue before investing significant work — we'd rather answer questions up front than have a contribution blocked at merge time.
