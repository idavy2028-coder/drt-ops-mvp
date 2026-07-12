CREATE TABLE user_accounts (
  id UUID PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  display_name VARCHAR(120) NOT NULL,
  password_hash VARCHAR(100) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  organization_code VARCHAR(80),
  token_version BIGINT NOT NULL DEFAULT 0,
  must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE roles (
  code VARCHAR(40) PRIMARY KEY,
  name VARCHAR(80) NOT NULL,
  description VARCHAR(200) NOT NULL
);

INSERT INTO roles (code, name, description) VALUES
  ('SYSTEM_ADMIN', '系统管理员', '管理账号、规则与基础配置'),
  ('DISPATCHER', '调度员', '执行调度、人工复核和任务异常处理'),
  ('OPERATOR', '运营人员', '维护资源并录入运营需求'),
  ('AUDITOR', '审计人员', '只读查看决策、审计与指标');

CREATE TABLE user_roles (
  user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
  role_code VARCHAR(40) NOT NULL REFERENCES roles(code),
  PRIMARY KEY (user_id, role_code)
);

CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
  token_hash VARCHAR(128) NOT NULL UNIQUE,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  created_from VARCHAR(120) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
