import React, { useState } from 'react';
import { connect } from 'react-redux';
import { Alert, Form, Input, Button } from 'antd';
import { LoginOutlined } from '@ant-design/icons';
import { login } from '../../actions/auth';
import largeLogo from './libeufin-logo-large.png';
import './Login.less';

interface Props {
  loginConnect: (nexusURL: string, username: string, password: string) => any;
}

const Login = ({ loginConnect }: Props) => {
  const [nexusURL, setNexusURL] = useState('localhost:5000');
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('x');
  const [authenticationFailure, setAuthenticationFailure] = useState(false);

  const layout = {
    wrapperCol: { span: 32 },
  };

  const login = () => {
    loginConnect(nexusURL, username, password)
      .then(() => {
        setAuthenticationFailure(false);
      })
      .catch((err) => setAuthenticationFailure(true));
  };

  const enterPressed = (event) => {
    let code = event.keyCode || event.which;
    if (code === 13) {
      login();
    }
  };

  return (
    <div className="login">
      {authenticationFailure ? (
        <Alert
          message="Error"
          description="Invalid credentials"
          type="error"
          showIcon
        />
      ) : null}
      <img className="img" src={largeLogo} alt="LibEuFin large logo" />
      <Form {...layout} size="large">
        <Form.Item>
          <Input
            placeholder="Nexus Server URL"
            defaultValue="localhost:5000"
            onChange={(e) => setNexusURL(e.target.value)}
          />
        </Form.Item>
        <Form.Item>
          <Input
            placeholder="Username"
            onChange={(e) => setUsername(e.target.value)}
            onKeyPress={(e) => enterPressed(e)}
          />
        </Form.Item>
        <Form.Item>
          <Input.Password
            placeholder="Password"
            onChange={(e) => setPassword(e.target.value)}
            onKeyPress={(e) => enterPressed(e)}
          />
        </Form.Item>
        <div className="button">
          <Button
            type="primary"
            icon={<LoginOutlined />}
            onClick={() => login()}
          >
            Login
          </Button>
        </div>
      </Form>
    </div>
  );
};

const mapDispatchToProps = {
  loginConnect: login,
};

export default connect(null, mapDispatchToProps)(Login);
