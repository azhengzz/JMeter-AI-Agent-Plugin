# Git Sampler
> **Source**: Gitee QA extension (third-party plugin `com.gitee.qa.jmeter`, requires the corresponding plugin)

## Description

Git Sampler executes Git operations against remote or local repositories. It supports SSH and HTTP protocols for authentication and provides 6 Git actions: Clone, Add, Commit, Push, Pull, and Branch.

This component is useful for performance testing Git server throughput, CI/CD pipeline simulation, and repository operation benchmarking.

## Parameters

| Property | Required | Default | Description | Example |
|----------|----------|---------|-------------|---------|
| `GitSampler.use_ssh_protocol` | No | `true` | Use SSH protocol for Git operations. | `true` |
| `GitSampler.use_http_protocol` | No | `false` | Use HTTP protocol for Git operations. | `false` |
| `GitSampler.ssh_key_path` | No | `""` | Path to SSH private key (id_rsa). Use forward slashes on Windows. | `"C:/Users/test/.ssh/id_rsa"` |
| `GitSampler.user_name` | No | `""` | Username for HTTP authentication. | `"admin"` |
| `GitSampler.user_password` | No | `""` | Password for HTTP authentication. | `"password"` |
| `GitSampler.action` | Yes | `"Clone"` | Git operation to perform. One of: `Clone`, `Add`, `Commit`, `Push`, `Pull`, `Branch`. | `"Push"` |
| `GitSampler.Arguments` | Varies | — | Action-specific key-value arguments (see below). | — |

## Actions and Arguments

| Action | Description | Required Arguments | Optional Arguments |
|--------|-------------|--------------------|--------------------|
| `Clone` | Clone a remote repository | `<repository>`, `<directory>` | `--branch` |
| `Add` | Stage file changes | `<repo-path>`, `<pathspec>` | — |
| `Commit` | Commit staged changes | `<repo-path>`, `--message` | `--author-name`, `--author-email` |
| `Push` | Push commits to remote | `<repo-path>`, `--force`, `<refspec>` | — |
| `Pull` | Pull changes from remote | `<repo-path>` | — |
| `Branch` | Create a branch | `<repo-path>`, `<branch-action>`, `<branch-name>` | — |

### Argument Keys

| Key | Required | Description | Example |
|-----|----------|-------------|---------|
| `<repository>` | Yes (Clone) | Remote repository URL to clone from | `"git@github.com:user/repo.git"` |
| `<directory>` | Yes (Clone) | Local directory to clone into | `"/tmp/test-repo"` |
| `--branch` | No (Clone) | Branch to checkout after clone | `"main"` |
| `<repo-path>` | Yes (others) | Path to local Git repository | `"/tmp/test-repo"` |
| `<pathspec>` | Yes (Add) | File pattern to stage (use `.` for all) | `"."` |
| `--message` | Yes (Commit) | Commit message | `"feat: add new feature"` |
| `--author-name` | No (Commit) | Committer name | `"Zhang San"` |
| `--author-email` | No (Commit) | Committer email | `"zhangsan@example.com"` |
| `--force` | Yes (Push) | Force push (`"true"` or `"false"`) | `"false"` |
| `<refspec>` | Yes (Push) | Refspec for push (e.g. `refs/heads/main`) | `"refs/heads/main"` |
| `<branch-action>` | Yes (Branch) | Branch action (currently only `"create"`) | `"create"` |
| `<branch-name>` | Yes (Branch) | Branch name to create | `"feature-test"` |

## Usage Examples

### Example 1: Clone Repository via SSH

```
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "克隆仓库"
- properties:
  - GitSampler.use_ssh_protocol: "true"
  - GitSampler.use_http_protocol: "false"
  - GitSampler.ssh_key_path: "C:/Users/test/.ssh/id_rsa"
  - GitSampler.action: "Clone"
  - GitSampler.Arguments:
    - "<repository>": "git@github.com:user/repo.git"
    - "<directory>": "/tmp/test-repo"
    - "--branch": "main"
```

### Example 2: Clone Repository via HTTP

```
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "HTTP克隆仓库"
- properties:
  - GitSampler.use_ssh_protocol: "false"
  - GitSampler.use_http_protocol: "true"
  - GitSampler.user_name: "admin"
  - GitSampler.user_password: "password"
  - GitSampler.action: "Clone"
  - GitSampler.Arguments:
    - "<repository>": "https://github.com/user/repo.git"
    - "<directory>": "/tmp/test-repo"
```

### Example 3: Add, Commit, and Push

```
// Step 1: Stage files
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "Git Add"
- properties:
  - GitSampler.use_ssh_protocol: "true"
  - GitSampler.ssh_key_path: "C:/Users/test/.ssh/id_rsa"
  - GitSampler.action: "Add"
  - GitSampler.Arguments:
    - "<repo-path>": "/tmp/test-repo"
    - "<pathspec>": "."

// Step 2: Commit
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "Git Commit"
- properties:
  - GitSampler.action: "Commit"
  - GitSampler.Arguments:
    - "<repo-path>": "/tmp/test-repo"
    - "--message": "feat: automated commit"
    - "--author-name": "CI Bot"
    - "--author-email": "ci@example.com"

// Step 3: Push
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "Git Push"
- properties:
  - GitSampler.use_ssh_protocol: "true"
  - GitSampler.ssh_key_path: "C:/Users/test/.ssh/id_rsa"
  - GitSampler.action: "Push"
  - GitSampler.Arguments:
    - "<repo-path>": "/tmp/test-repo"
    - "--force": "false"
    - "<refspec>": "refs/heads/main"
```

### Example 4: Pull Changes

```
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "拉取更新"
- properties:
  - GitSampler.use_http_protocol: "true"
  - GitSampler.user_name: "admin"
  - GitSampler.user_password: "password"
  - GitSampler.action: "Pull"
  - GitSampler.Arguments:
    - "<repo-path>": "/tmp/test-repo"
```

### Example 5: Create Branch

```
create_jmeter_element with:
- elementType: "gitsampler"
- elementName: "创建分支"
- properties:
  - GitSampler.action: "Branch"
  - GitSampler.Arguments:
    - "<repo-path>": "/tmp/test-repo"
    - "<branch-action>": "create"
    - "<branch-name>": "feature-test"
```

## Notes

- SSH and HTTP protocols are mutually exclusive — enable only one
- On Windows, use `/` (forward slash) in SSH key paths, e.g. `C:/Users/test/.ssh/id_rsa`
- If SSH authentication fails with `invalid privatekey`, regenerate the key with `ssh-keygen -t rsa` or `ssh-keygen -t rsa -m PEM`
- If SSH authentication fails with `Auth fail`, ensure the public key is added to the Git server
- The Branch action currently only supports `create`
- Clone is typically the first operation, followed by Add → Commit → Push workflows
