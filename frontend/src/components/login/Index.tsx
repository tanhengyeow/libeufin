import React, { useState } from 'react';
import { connect } from 'react-redux';
import { Form, Input, Button } from 'antd';
import { LoginOutlined } from '@ant-design/icons';
import { login } from '../../actions/auth';
import largeLogo from './libeufin-logo-large.png';
import './Login.less';

interface Props {
  loginConnect: (nexusURL: string, username: string, password: string) => void;
}

const Login = ({ loginConnect }: Props) => {
  const [nexusURL, setNexusURL] = useState('localhost:5000');
  const [username, setUsername] = useState('user1');
  const [password, setPassword] = useState('user1');

  const layout = {
    wrapperCol: { span: 32 },
  };

  return (
    <div className="login">
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
          />
        </Form.Item>
        <Form.Item>
          <Input.Password
            placeholder="Password"
            onChange={(e) => setPassword(e.target.value)}
          />
        </Form.Item>
        <div className="button">
          <Button
            type="primary"
            icon={<LoginOutlined />}
            onClick={() => loginConnect(nexusURL, username, password)}
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
