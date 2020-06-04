import React from 'react';
import { connect } from 'react-redux';
import { Route, Router } from 'react-router-dom';
import history from './history';
import Pages from './routes/Pages';
import { checkAuthentication } from './actions/auth';
import { Auth } from './types';
import './App.less';

interface Props {
  checkAuthenticationConnect: () => void;
  isAuthenticated: boolean | null;
}

const App = ({ checkAuthenticationConnect, isAuthenticated }: Props) => {
  React.useEffect(() => {
    checkAuthenticationConnect();
  });

  const app =
    isAuthenticated !== null ? (
      <Router history={history}>
        <Route component={Pages} />
      </Router>
    ) : null;

  return <div className="App">{app}</div>;
};

const mapStateToProps = (state: Auth) => ({
  isAuthenticated: state.isAuthenticated,
});

const mapDispatchToProps = {
  checkAuthenticationConnect: checkAuthentication,
};

export default connect(mapStateToProps, mapDispatchToProps)(App);
