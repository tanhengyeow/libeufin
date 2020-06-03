import * as React from 'react';
import { connect } from 'react-redux';
import { Link } from 'react-router-dom';
import { logout } from '../../actions/auth';

interface Props {
  logoutConnect: () => void;
}

const Home = ({ logoutConnect }: Props) => (
  <>
    <p>Home page</p>
    <ul>
      <li>
        <Link to="/activity">Activity</Link>
      </li>
      <li>
        <Link to="/bank-accounts">Bank Accounts</Link>
      </li>
      <li>
        <Link to="/non-existent-link">Non existent link</Link>
      </li>
    </ul>
    <button type="button" onClick={logoutConnect}>
      Logout
    </button>
  </>
);

const mapDispatchToProps = {
  logoutConnect: logout,
};

export default connect(null, mapDispatchToProps)(Home);
