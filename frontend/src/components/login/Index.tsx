/*
 This file is part of GNU Taler
 (C) 2020 Taler Systems S.A.

 GNU Taler is free software; you can redistribute it and/or modify it under the
 terms of the GNU General Public License as published by the Free Software
 Foundation; either version 3, or (at your option) any later version.

 GNU Taler is distributed in the hope that it will be useful, but WITHOUT ANY
 WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 A PARTICULAR PURPOSE.  See the GNU General Public License for more details.

 You should have received a copy of the GNU General Public License along with
 GNU Taler; see the file COPYING.  If not, see <http://www.gnu.org/licenses/>
 */

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
  const [
    authenticationFailureMessage,
    setAuthenticationFailureMessage,
  ] = useState('');

  const layout = {
    wrapperCol: { span: 32 },
  };

  const login = () => {
    loginConnect(nexusURL, username, password)
      .then(() => {
        setAuthenticationFailure(false);
      })
      .catch((err) => {
        setAuthenticationFailure(true);
        setAuthenticationFailureMessage(err);
      });
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
          description={String(authenticationFailureMessage)}
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
